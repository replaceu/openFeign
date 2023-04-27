/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import feign.Client;
import feign.Contract;
import feign.ExceptionPropagationPolicy;
import feign.Feign;
import feign.Logger;
import feign.QueryMapEncoder;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.ribbon.LoadBalancerFeignClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 * @author Matt King
 * @author Olga Maciaszek-Sharma
 * @author Ilia Ilinykh
 */
class FeignClientFactoryBean
		implements FactoryBean<Object>, InitializingBean, ApplicationContextAware {

	/***********************************
	 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some
	 * lifecycle race condition.
	 ***********************************/

	/**
	 * 接口的类型
	 */
	private Class<?> type;

	/**
	 * 指定的名称
	 */
	private String name;

	/**
	 * 服务的请求接口地址
	 */
	private String url;

	/**
	 * 上下文id，如果没有直接指定会通过 serviceId或者name进行获取
	 */
	private String contextId;

	/**
	 * 调用路径
	 */
	private String path;

	/**
	 * 解码器找不到时是否返回404
	 */
	private boolean decode404;

	/**
	 * 是否需要继承父上下文
	 */
	private boolean inheritParentContext = true;

	/**
	 * spring容器
	 */
	private ApplicationContext applicationContext;

	/**
	 * 回调类
	 */
	private Class<?> fallback = void.class;

	/**
	 * 回调类的工厂类
	 */
	private Class<?> fallbackFactory = void.class;

	/**
	 * 读取超时的时间
	 * 默认读取超时60秒
	 * 连接超时10秒
	 */
	private int readTimeoutMillis = new Request.Options().readTimeoutMillis();

	/**
	 * 连接超时的时间
	 */
	private int connectTimeoutMillis = new Request.Options().connectTimeoutMillis();

	@Override
	public void afterPropertiesSet() {
		Assert.hasText(contextId, "Context id must be set");
		Assert.hasText(name, "Name must be set");
	}

	protected Feign.Builder feign(FeignContext context) {
		FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
		Logger logger = loggerFactory.create(type);

		//先从容器中获取到 Builder 类型，在 FeignClientsConfiguration 就进行了初始化，如果导入了hystrix的包，那么就会创建为 feign.hystrix.HystrixFeign.Builder 类型
		Feign.Builder builder = get(context, Feign.Builder.class)
				// required values
				.logger(logger)
				//容器中获取到 Encoder：默认类型 SpringEncoder
				.encoder(get(context, Encoder.class))
				//容器中获取到 Decoder：默认类型 OptionalDecoder中内嵌的 SpringDecoder类型进行处理
				.decoder(get(context, Decoder.class))
				//容器中获取到 Contract：默认类型 SpringMvcContract
				.contract(get(context, Contract.class));
		// @formatter:on
		//再通过上下文对象进行构建
		configureFeign(context, builder);

		return builder;
	}

	protected void configureFeign(FeignContext context, Feign.Builder builder) {
		//获取到配置文件对象
		FeignClientProperties properties = applicationContext
				.getBean(FeignClientProperties.class);

		//获取到FeignClientConfigurer对象，通过子容器中
		FeignClientConfigurer feignClientConfigurer = getOptional(context,
				FeignClientConfigurer.class);
		//设置是否需要继承父容器
		setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());

		if (properties != null && inheritParentContext) {
			if (properties.isDefaultToProperties()) {
				configureUsingConfiguration(context, builder);
				configureUsingProperties(
						properties.getConfig().get(properties.getDefaultConfig()),
						builder);
				configureUsingProperties(properties.getConfig().get(contextId), builder);
			}
			else {
				configureUsingProperties(
						properties.getConfig().get(properties.getDefaultConfig()),
						builder);
				configureUsingProperties(properties.getConfig().get(contextId), builder);
				configureUsingConfiguration(context, builder);
			}
		}
		else {
			//通过配置文件进行配置
			configureUsingConfiguration(context, builder);
		}
	}

	/**
	 * 使用配置进行配置构建器
	 * Logger.Level：日志级别
	 * Retryer：重试器
	 * ErrorDecoder：错误解码
	 * FeignErrorDecoderFactory：错误解码工厂
	 * Request.Options：配置读取和连接超时的时间
	 * RequestInterceptor：请求的拦截器
	 * QueryMapEncoder：查询映射的编码
	 * ExceptionPropagationPolicy：异常传播的策略
	 *
	 * @param context
	 * @param builder
	 */
	protected void configureUsingConfiguration(FeignContext context,
			Feign.Builder builder) {
		Logger.Level level = getInheritedAwareOptional(context, Logger.Level.class);
		if (level != null) {
			builder.logLevel(level);
		}
		//获取到重试器
		Retryer retryer = getInheritedAwareOptional(context, Retryer.class);
		if (retryer != null) {
			builder.retryer(retryer);
		}
		//获取到错误的解码器
		ErrorDecoder errorDecoder = getInheritedAwareOptional(context,
				ErrorDecoder.class);
		if (errorDecoder != null) {
			builder.errorDecoder(errorDecoder);
		}
		else {
			//获取到 FeignErrorDecoderFactory，通过工厂对象来进行创建
			FeignErrorDecoderFactory errorDecoderFactory = getOptional(context,
					FeignErrorDecoderFactory.class);
			if (errorDecoderFactory != null) {
				ErrorDecoder factoryErrorDecoder = errorDecoderFactory.create(type);
				builder.errorDecoder(factoryErrorDecoder);
			}
		}
		//获取到 Options 配置对象来设置连接超时和读取超时的时间
		Request.Options options = getInheritedAwareOptional(context,
				Request.Options.class);
		if (options != null) {
			builder.options(options);
			readTimeoutMillis = options.readTimeoutMillis();
			connectTimeoutMillis = options.connectTimeoutMillis();
		}
		//从容器中获取到拦截器 RequestInterceptor
		Map<String, RequestInterceptor> requestInterceptors = getInheritedAwareInstances(
				context, RequestInterceptor.class);
		if (requestInterceptors != null) {
			List<RequestInterceptor> interceptors = new ArrayList<>(
					requestInterceptors.values());
			//排序
			AnnotationAwareOrderComparator.sort(interceptors);
			builder.requestInterceptors(interceptors);
		}
		QueryMapEncoder queryMapEncoder = getInheritedAwareOptional(context,
				QueryMapEncoder.class);
		if (queryMapEncoder != null) {
			builder.queryMapEncoder(queryMapEncoder);
		}
		if (decode404) {
			builder.decode404();
		}
		//配置异常传播的策略
		ExceptionPropagationPolicy exceptionPropagationPolicy = getInheritedAwareOptional(
				context, ExceptionPropagationPolicy.class);
		if (exceptionPropagationPolicy != null) {
			builder.exceptionPropagationPolicy(exceptionPropagationPolicy);
		}
	}

	protected void configureUsingProperties(
			FeignClientProperties.FeignClientConfiguration config,
			Feign.Builder builder) {
		if (config == null) {
			return;
		}

		if (config.getLoggerLevel() != null) {
			builder.logLevel(config.getLoggerLevel());
		}

		connectTimeoutMillis = config.getConnectTimeout() != null
				? config.getConnectTimeout() : connectTimeoutMillis;
		readTimeoutMillis = config.getReadTimeout() != null ? config.getReadTimeout()
				: readTimeoutMillis;

		builder.options(new Request.Options(connectTimeoutMillis, TimeUnit.MILLISECONDS,
				readTimeoutMillis, TimeUnit.MILLISECONDS, true));

		if (config.getRetryer() != null) {
			Retryer retryer = getOrInstantiate(config.getRetryer());
			builder.retryer(retryer);
		}

		if (config.getErrorDecoder() != null) {
			ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
			builder.errorDecoder(errorDecoder);
		}

		if (config.getRequestInterceptors() != null
				&& !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
				RequestInterceptor interceptor = getOrInstantiate(bean);
				builder.requestInterceptor(interceptor);
			}
		}

		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder.decode404();
			}
		}

		if (Objects.nonNull(config.getEncoder())) {
			builder.encoder(getOrInstantiate(config.getEncoder()));
		}

		if (Objects.nonNull(config.getDefaultRequestHeaders())) {
			builder.requestInterceptor(requestTemplate -> requestTemplate
					.headers(config.getDefaultRequestHeaders()));
		}

		if (Objects.nonNull(config.getDefaultQueryParameters())) {
			builder.requestInterceptor(requestTemplate -> requestTemplate
					.queries(config.getDefaultQueryParameters()));
		}

		if (Objects.nonNull(config.getDecoder())) {
			builder.decoder(getOrInstantiate(config.getDecoder()));
		}

		if (Objects.nonNull(config.getContract())) {
			builder.contract(getOrInstantiate(config.getContract()));
		}

		if (Objects.nonNull(config.getExceptionPropagationPolicy())) {
			builder.exceptionPropagationPolicy(config.getExceptionPropagationPolicy());
		}
	}

	private <T> T getOrInstantiate(Class<T> tClass) {
		try {
			return applicationContext.getBean(tClass);
		}
		catch (NoSuchBeanDefinitionException e) {
			return BeanUtils.instantiateClass(tClass);
		}
	}

	protected <T> T get(FeignContext context, Class<T> type) {
		T instance = context.getInstance(contextId, type);
		if (instance == null) {
			throw new IllegalStateException(
					"No bean found of type " + type + " for " + contextId);
		}
		return instance;
	}

	protected <T> T getOptional(FeignContext context, Class<T> type) {
		return context.getInstance(contextId, type);
	}

	protected <T> T getInheritedAwareOptional(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return getOptional(context, type);
		}
		else {
			return context.getInstanceWithoutAncestors(contextId, type);
		}
	}

	protected <T> Map<String, T> getInheritedAwareInstances(FeignContext context,
			Class<T> type) {
		if (inheritParentContext) {
			return context.getInstances(contextId, type);
		}
		else {
			return context.getInstancesWithoutAncestors(contextId, type);
		}
	}

	protected <T> T loadBalance(Feign.Builder builder, FeignContext context,
			HardCodedTarget<T> target) {
		Client client = getOptional(context, Client.class);
		if (client != null) {
			builder.client(client);
			Targeter targeter = get(context, Targeter.class);
			return targeter.target(this, builder, context, target);
		}

		throw new IllegalStateException(
				"No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-netflix-ribbon?");
	}

	@Override
	public Object getObject() throws Exception {
		return getTarget();
	}

	/**
	 * 当前方法主要是通过从容器中获取到 Feign.Builder 类型进行配置，而 Feign.Builder 有两个实现类目前
	 * DefaultTarget：当class路径下面没有 feign.hystrix.HystrixFeign 类时就创建当前
	 * HystrixTargeter：当路径下面有 feign.hystrix.HystrixFeign 创建当前
	 */
	<T> T getTarget() {
		//从spring中获取到 FeignContext 对象，在 FeignAutoConfiguration 进行的自动导入
		FeignContext context = applicationContext.getBean(FeignContext.class);
		/**
		 * 根据上下文创建 Builder 进行创建
		 * context 继承至 NamedContextFactory 会为每个接口都创建一个容器进行存储
		 * 从容器中获取了 Feign.Builder 类型进行构建，并且通过配置信息进行配置，以下属性都是通过子容器中的配置进行获取
		 *
		 * Logger.Level：日志级别
		 * Retryer：重试器
		 * ErrorDecoder：错误解码
		 * FeignErrorDecoderFactory：错误解码工厂
		 * Request.Options：配置读取和连接超时的时间
		 * RequestInterceptor：请求的拦截器
		 * QueryMapEncoder：查询映射的编码
		 * ExceptionPropagationPolicy：异常传播的策略
		 */
		Feign.Builder builder = feign(context);
		/**
		 * 处理url地址，如果没有指定url地址，拼接url地址为 http://name属性的值
		 */
		if (!StringUtils.hasText(url)) {
			if (!name.startsWith("http")) {
				url = "http://" + name;
			}
			else {
				url = name;
			}
			url += cleanPath();
			return (T) loadBalance(builder, context,
					new HardCodedTarget<>(type, name, url));
		}
		//如果指定了url，并且不是http开头，默认拼接上http
		if (StringUtils.hasText(url) && !url.startsWith("http")) {
			url = "http://" + url;
		}
		//处理以下 /
		String url = this.url + cleanPath();
		/**
		 * 从容器中获取到客户端对象，这个 Client 接口就是后续执行请求的类
		 * 一般都是通过组件自定义实现，是否是负载的客户端等
		 */
		Client client = getOptional(context, Client.class);
		if (client != null) {
			if (client instanceof LoadBalancerFeignClient) {
				// not load balancing because we have a url,
				// but ribbon is on the classpath, so unwrap
				client = ((LoadBalancerFeignClient) client).getDelegate();
			}
			if (client instanceof FeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap
				client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
			}
			builder.client(client);
		}
		/**
		 * 获取到 Targeter 通过target类型进行创建接口的代理对象有两个实现类
		 * DefaultTarget：当class路径下面没有 feign.hystrix.HystrixFeign 类时就创建当前
		 * HystrixTargeter：当路径下面有 feign.hystrix.HystrixFeign 创建当前
		 */
		Targeter targeter = get(context, Targeter.class);
		return (T) targeter.target(this, builder, context,
				new HardCodedTarget<>(type, name, url));
	}

	private String cleanPath() {
		String path = this.path.trim();
		if (StringUtils.hasLength(path)) {
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public Class<?> getObjectType() {
		return type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	public Class<?> getType() {
		return type;
	}

	public void setType(Class<?> type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContextId() {
		return contextId;
	}

	public void setContextId(String contextId) {
		this.contextId = contextId;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isDecode404() {
		return decode404;
	}

	public void setDecode404(boolean decode404) {
		this.decode404 = decode404;
	}

	public boolean isInheritParentContext() {
		return inheritParentContext;
	}

	public void setInheritParentContext(boolean inheritParentContext) {
		this.inheritParentContext = inheritParentContext;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		this.applicationContext = context;
	}

	public Class<?> getFallback() {
		return fallback;
	}

	public void setFallback(Class<?> fallback) {
		this.fallback = fallback;
	}

	public Class<?> getFallbackFactory() {
		return fallbackFactory;
	}

	public void setFallbackFactory(Class<?> fallbackFactory) {
		this.fallbackFactory = fallbackFactory;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		FeignClientFactoryBean that = (FeignClientFactoryBean) o;
		return Objects.equals(applicationContext, that.applicationContext)
				&& decode404 == that.decode404
				&& inheritParentContext == that.inheritParentContext
				&& Objects.equals(fallback, that.fallback)
				&& Objects.equals(fallbackFactory, that.fallbackFactory)
				&& Objects.equals(name, that.name) && Objects.equals(path, that.path)
				&& Objects.equals(type, that.type) && Objects.equals(url, that.url);
	}

	@Override
	public int hashCode() {
		return Objects.hash(applicationContext, decode404, inheritParentContext, fallback,
				fallbackFactory, name, path, type, url);
	}

	@Override
	public String toString() {
		return new StringBuilder("FeignClientFactoryBean{").append("type=").append(type)
				.append(", ").append("name='").append(name).append("', ").append("url='")
				.append(url).append("', ").append("path='").append(path).append("', ")
				.append("decode404=").append(decode404).append(", ")
				.append("inheritParentContext=").append(inheritParentContext).append(", ")
				.append("applicationContext=").append(applicationContext).append(", ")
				.append("fallback=").append(fallback).append(", ")
				.append("fallbackFactory=").append(fallbackFactory).append("}")
				.toString();
	}

}
