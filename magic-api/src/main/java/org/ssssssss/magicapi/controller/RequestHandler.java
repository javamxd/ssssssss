package org.ssssssss.magicapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.core.io.InputStreamSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.ssssssss.magicapi.config.MagicConfiguration;
import org.ssssssss.magicapi.config.MappingHandlerMapping;
import org.ssssssss.magicapi.config.Valid;
import org.ssssssss.magicapi.context.CookieContext;
import org.ssssssss.magicapi.context.RequestContext;
import org.ssssssss.magicapi.context.SessionContext;
import org.ssssssss.magicapi.exception.ValidateException;
import org.ssssssss.magicapi.interceptor.RequestInterceptor;
import org.ssssssss.magicapi.logging.LogInfo;
import org.ssssssss.magicapi.logging.MagicLoggerContext;
import org.ssssssss.magicapi.model.*;
import org.ssssssss.magicapi.modules.ResponseModule;
import org.ssssssss.magicapi.provider.ResultProvider;
import org.ssssssss.magicapi.script.ScriptManager;
import org.ssssssss.magicapi.utils.PatternUtils;
import org.ssssssss.script.MagicScriptContext;
import org.ssssssss.script.MagicScriptDebugContext;
import org.ssssssss.script.exception.MagicScriptAssertException;
import org.ssssssss.script.exception.MagicScriptException;
import org.ssssssss.script.functions.ObjectConvertExtension;
import org.ssssssss.script.parsing.Span;
import org.ssssssss.script.parsing.ast.literal.BooleanLiteral;
import org.ssssssss.script.reflection.JavaInvoker;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS;
import static org.ssssssss.magicapi.model.Constants.*;

public class RequestHandler extends MagicController {

	private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);

	private final ResultProvider resultProvider;

	private static final Map<String, Object> EMPTY_MAP = new HashMap<>();

	public RequestHandler(MagicConfiguration configuration) {
		super(configuration);
		this.resultProvider = configuration.getResultProvider();
	}

	/**
	 * 测试入口、实际请求入口
	 */
	@ResponseBody
	@Valid(requireLogin = false)    // 无需验证是否要登录
	public Object invoke(HttpServletRequest request, HttpServletResponse response,
						 @PathVariable(required = false) Map<String, Object> pathVariables,
						 @RequestParam(required = false) Map<String, Object> parameters) throws Throwable {
		RequestEntity requestEntity = new RequestEntity(request, response, isRequestedFromTest(request), parameters, pathVariables);
		if (requestEntity.isRequestedFromTest()) {
			response.setHeader(HEADER_RESPONSE_WITH_MAGIC_API, CONST_STRING_TRUE);
			response.setHeader(ACCESS_CONTROL_EXPOSE_HEADERS, HEADER_RESPONSE_WITH_MAGIC_API);
			if(requestEntity.isRequestedFromContinue()){
				return invokeContinueRequest(requestEntity);
			}
		}
		if (requestEntity.getApiInfo() == null) {
			logger.error("{}找不到对应接口", request.getRequestURI());
			return buildResult(requestEntity, API_NOT_FOUND, "接口不存在");
		}
		Map<String, Object> headers = new HashMap<String, Object>() {
			@Override
			public Object get(Object key) {
				return request.getHeader(key.toString());
			}
		};
		requestEntity.setHeaders(headers);
		List<Path> paths = new ArrayList<>(requestEntity.getApiInfo().getPaths());
		MappingHandlerMapping.findGroups(requestEntity.getApiInfo().getGroupId())
				.stream()
				.flatMap(it -> it.getPaths().stream())
				.filter(it -> !paths.contains(it))
				.forEach(paths::add);
		Object bodyValue = readRequestBody(requestEntity.getRequest());
		try {
			// 验证参数
			doValidate("参数", requestEntity.getApiInfo().getParameters(), parameters, PARAMETER_INVALID);
			// 验证 header
			doValidate("header", requestEntity.getApiInfo().getHeaders(), headers, HEADER_INVALID);
			// 验证 path
			doValidate("path", paths, requestEntity.getPathVariables(), PATH_VARIABLE_INVALID);
			BaseDefinition requestBody = requestEntity.getApiInfo().getRequestBodyDefinition();
			if (requestBody != null && requestBody.getChildren().size() > 0) {
				requestBody.setName(StringUtils.defaultIfBlank(requestBody.getName(), "root"));
				doValidate(VAR_NAME_REQUEST_BODY, Collections.singletonList(requestBody), new HashMap<String, Object>() {{
					put(requestBody.getName(), bodyValue);
				}}, BODY_INVALID);
			}
		} catch (ValidateException e) {
			Object value = resultProvider.buildResult(requestEntity, RESPONSE_CODE_INVALID, e.getMessage());
			return requestEntity.isRequestedFromTest() ? new JsonBean<>(e.getJsonCode(), value) : value;
		} catch (Throwable root) {
			return processException(requestEntity, root);
		}
		MagicScriptContext context = createMagicScriptContext(requestEntity, bodyValue);
		requestEntity.setMagicScriptContext(context);
		RequestContext.setRequestEntity(requestEntity);
		Object value;
		// 执行前置拦截器
		if ((value = doPreHandle(requestEntity)) != null) {
			if (requestEntity.isRequestedFromTest()) {
				// 修正前端显示，当拦截器返回时，原样输出显示
				response.setHeader(HEADER_RESPONSE_WITH_MAGIC_API, CONST_STRING_FALSE);
			}
			return value;
		}
		return requestEntity.isRequestedFromTest() ?  invokeTestRequest(requestEntity) : invokeRequest(requestEntity);
	}

	private Object buildResult(RequestEntity requestEntity, JsonCode code, Object data) {
		return resultProvider.buildResult(requestEntity, code.getCode(), code.getMessage(), data);
	}


	private boolean doValidateBody(String comment, BaseDefinition parameter, Map<String, Object> parameters, JsonCode jsonCode, Class<?> target) {
		if (!parameter.isRequired() && parameters.isEmpty()) {
			return true;
		}
		if (parameter.isRequired() && !BooleanLiteral.isTrue(parameters.get(parameter.getName()))) {
			throw new ValidateException(jsonCode, StringUtils.defaultIfBlank(parameter.getError(), String.format("%s[%s]为必填项", comment, parameter.getName())));
		}
		Object value = parameters.get(parameter.getName());
		if (value != null && !target.isAssignableFrom(value.getClass())) {
			throw new ValidateException(jsonCode, StringUtils.defaultIfBlank(parameter.getError(), String.format("%s[%s]数据类型错误", comment, parameter.getName())));
		}
		return false;
	}

	private <T extends BaseDefinition> void doValidate(String comment, List<T> validateParameters, Map<String, Object> parameters, JsonCode jsonCode) {
		parameters = parameters != null ? parameters : EMPTY_MAP;
		for (BaseDefinition parameter : validateParameters) {
			// 针对requestBody多层级的情况
			if (VAR_NAME_REQUEST_BODY_VALUE_TYPE_OBJECT.equalsIgnoreCase(parameter.getDataType().getJavascriptType())) {
				if (doValidateBody(comment, parameter, parameters, jsonCode, Map.class)) {
					continue;
				}
				doValidate(VAR_NAME_REQUEST_BODY, parameter.getChildren(), (Map) parameters.get(parameter.getName()), jsonCode);
			} else if (VAR_NAME_REQUEST_BODY_VALUE_TYPE_ARRAY.equalsIgnoreCase(parameter.getDataType().getJavascriptType())) {
				if (doValidateBody(comment, parameter, parameters, jsonCode, List.class)) {
					continue;
				}
				List list = (List) parameters.get(parameter.getName());
				if (list != null) {
					for (Object value : list) {
						List<BaseDefinition> definitions = parameter.getChildren();
						doValidate(VAR_NAME_REQUEST_BODY, definitions, new HashMap<String, Object>() {{
							put("", value);
						}}, jsonCode);
					}
				}

			} else if (StringUtils.isNotBlank(parameter.getName())) {
				String requestValue = StringUtils.defaultIfBlank(Objects.toString(parameters.get(parameter.getName()), EMPTY), Objects.toString(parameter.getDefaultValue(), EMPTY));
				if (StringUtils.isBlank(requestValue)) {
					if (!parameter.isRequired()) {
						continue;
					}
					throw new ValidateException(jsonCode, StringUtils.defaultIfBlank(parameter.getError(), String.format("%s[%s]为必填项", comment, parameter.getName())));
				}
				try {
					Object value = convertValue(parameter.getDataType(), parameter.getName(), requestValue);
					if (VALIDATE_TYPE_PATTERN.equals(parameter.getValidateType())) {    // 正则验证
						String expression = parameter.getExpression();
						if (StringUtils.isNotBlank(expression) && !PatternUtils.match(Objects.toString(value, EMPTY), expression)) {
							throw new ValidateException(jsonCode, StringUtils.defaultIfBlank(parameter.getError(), String.format("%s[%s]不满足正则表达式", comment, parameter.getName())));
						}
					}
					parameters.put(parameter.getName(), value);
				} catch (Exception e) {
					throw new ValidateException(jsonCode, StringUtils.defaultIfBlank(parameter.getError(), String.format("%s[%s]不合法", comment, parameter.getName())));
				}
			}
		}
		// 取出表达式验证的参数
		List<BaseDefinition> validates = validateParameters.stream().filter(it -> VALIDATE_TYPE_EXPRESSION.equals(it.getValidateType()) && StringUtils.isNotBlank(it.getExpression())).collect(Collectors.toList());
		for (BaseDefinition parameter : validates) {
			MagicScriptContext context = new MagicScriptContext();
			// 将其他参数也放置脚本中，以实现“依赖”的情况
			context.putMapIntoContext(parameters);
			Object value = parameters.get(parameter.getName());
			if (value != null) {
				// 设置自身变量
				context.set(EXPRESSION_DEFAULT_VAR_NAME, value);
				if (!BooleanLiteral.isTrue(ScriptManager.executeExpression(parameter.getExpression(), context))) {
					throw new ValidateException(jsonCode, StringUtils.defaultIfBlank(parameter.getError(), String.format("%s[%s]不满足表达式", comment, parameter.getName())));
				}
			}
		}
	}

	/**
	 * 转换参数类型
	 */
	private Object convertValue(DataType dataType, String name, String value) {
		if (dataType == null) {
			return value;
		}
		try {
			if (dataType.isNumber()) {
				BigDecimal decimal = ObjectConvertExtension.asDecimal(value, null);
				if (decimal == null) {
					throw new IllegalArgumentException();
				}
				return dataType.getInvoker().invoke0(decimal, null);
			} else {
				JavaInvoker<Method> invoker = dataType.getInvoker();
				if (invoker != null) {
					List<Object> params = new ArrayList<>();
					if (dataType.isNeedName()) {
						params.add(name);
					}
					if (dataType.isNeedValue()) {
						params.add(value);
					}
					return invoker.invoke0(null, null, params.toArray());
				}
			}
			return value;
		} catch (Throwable throwable) {
			throw new IllegalArgumentException();
		}
	}

	private Object invokeContinueRequest(RequestEntity requestEntity) throws Exception {
		HttpServletRequest request = requestEntity.getRequest();
		MagicScriptDebugContext context = MagicScriptDebugContext.getDebugContext(requestEntity.getRequestedSessionId());
		if (context == null) {
			return new JsonBean<>(DEBUG_SESSION_NOT_FOUND, buildResult(requestEntity, DEBUG_SESSION_NOT_FOUND, null));
		}
		// 重置断点
		context.setBreakpoints(requestEntity.getRequestedBreakpoints());
		// 步进
		context.setStepInto(CONST_STRING_TRUE.equalsIgnoreCase(request.getHeader(HEADER_REQUEST_STEP_INTO)));
		try {
			context.singal();    //等待语句执行到断点或执行完毕
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		if (context.isRunning()) {    //判断是否执行完毕
			return new JsonBodyBean<>(1000, context.getId(), resultProvider.buildResult(requestEntity, 1000, context.getId()), context.getDebugInfo());
		} else if (context.isException()) {
			return resolveThrowableForTest(requestEntity, (Throwable) context.getReturnValue());
		}
		Object value = context.getReturnValue();
		// 执行后置拦截器
		if ((value = doPostHandle(requestEntity, value)) != null) {
			// 修正前端显示，当拦截器返回时，原样输出显示
			requestEntity.getResponse().setHeader(HEADER_RESPONSE_WITH_MAGIC_API, CONST_STRING_FALSE);
			// 后置拦截器不包裹
			return value;
		}
		return convertResult(requestEntity, context.getReturnValue());
	}

	private Object invokeTestRequest(RequestEntity requestEntity) {
		try {
			// 初始化debug操作
			MagicScriptDebugContext context = initializeDebug(requestEntity);
			Object result = ScriptManager.executeScript(requestEntity.getApiInfo().getScript(), requestEntity.getMagicScriptContext());
			if (context.isRunning()) {
				return new JsonBodyBean<>(1000, context.getId(), resultProvider.buildResult(requestEntity, 1000, context.getId(), result), result);
			} else if (context.isException()) {    //判断是否出现异常
				return resolveThrowableForTest(requestEntity, (Throwable) context.getReturnValue());
			}
			Object value = result;
			// 执行后置拦截器
			if ((value = doPostHandle(requestEntity, value)) != null) {
				// 修正前端显示，当拦截器返回时，原样输出显示
				requestEntity.getResponse().setHeader(HEADER_RESPONSE_WITH_MAGIC_API, CONST_STRING_FALSE);
				// 后置拦截器不包裹
				return value;
			}
			return convertResult(requestEntity, result);
		} catch (Exception e) {
			return resolveThrowableForTest(requestEntity, e);
		}
	}

	private Object invokeRequest(RequestEntity requestEntity) throws Throwable {
		try {
			Object result = ScriptManager.executeScript(requestEntity.getApiInfo().getScript(), requestEntity.getMagicScriptContext());
			Object value = result;
			// 执行后置拦截器
			if ((value = doPostHandle(requestEntity, value)) != null) {
				return value;
			}
			// 对返回结果包装处理
			return response(requestEntity, result);
		} catch (Throwable root) {
			return processException(requestEntity, root);
		} finally {
			RequestContext.remove();
		}
	}

	private Object processException(RequestEntity requestEntity, Throwable root) throws Throwable {
		Throwable parent = root;
		do {
			if (parent instanceof MagicScriptAssertException) {
				MagicScriptAssertException sae = (MagicScriptAssertException) parent;
				return resultProvider.buildResult(requestEntity, sae.getCode(), sae.getMessage());
			}
		} while ((parent = parent.getCause()) != null);
		if (configuration.isThrowException()) {
			throw root;
		}
		logger.error("接口{}请求出错", requestEntity.getRequest().getRequestURI(), root);
		return resultProvider.buildException(requestEntity, root);
	}

	/**
	 * 转换请求结果
	 */
	private Object convertResult(RequestEntity requestEntity, Object result) throws IOException {
		if (result instanceof ResponseEntity) {
			ResponseEntity<?> entity = (ResponseEntity<?>) result;
			List<String> headers = new ArrayList<>();
			for (Map.Entry<String, List<String>> entry : entity.getHeaders().entrySet()) {
				String key = entry.getKey();
				for (String value : entry.getValue()) {
					headers.add(HEADER_PREFIX_FOR_TEST + key);
					requestEntity.getResponse().addHeader(HEADER_PREFIX_FOR_TEST + key, value);
				}
			}
			headers.add(HEADER_RESPONSE_WITH_MAGIC_API);
			// 允许前端读取自定义的header（跨域情况）。
			requestEntity.getResponse().setHeader(ACCESS_CONTROL_EXPOSE_HEADERS, String.join(",", headers));
			if (entity.getHeaders().isEmpty()) {
				return ResponseEntity.ok(new JsonBean<>(entity.getBody()));
			}
			return ResponseEntity.ok(new JsonBean<>(convertToBase64(entity.getBody())));
		} else if (result instanceof ResponseModule.NullValue) {
			// 对于return response.end() 的特殊处理
			return new JsonBean<>(RESPONSE_CODE_SUCCESS, "empty.");
		}
		return new JsonBean<>(resultProvider.buildResult(requestEntity, result));
	}

	/**
	 * 将结果转为base64
	 */
	private String convertToBase64(Object value) throws IOException {
		if (value instanceof String || value instanceof Number) {
			return convertToBase64(value.toString().getBytes());
		} else if (value instanceof byte[]) {
			return Base64.getEncoder().encodeToString((byte[]) value);
		} else if (value instanceof InputStream) {
			return convertToBase64(IOUtils.toByteArray((InputStream) value));
		} else if (value instanceof InputStreamSource) {
			InputStreamSource iss = (InputStreamSource) value;
			return convertToBase64(iss.getInputStream());
		} else {
			return convertToBase64(new ObjectMapper().writeValueAsString(value));
		}
	}

	/**
	 * 解决异常
	 */
	private JsonBean<Object> resolveThrowableForTest(RequestEntity requestEntity, Throwable root) {
		MagicScriptException se = null;
		Throwable parent = root;
		do {
			if (parent instanceof MagicScriptAssertException) {
				MagicScriptAssertException sae = (MagicScriptAssertException) parent;
				return new JsonBean<>(resultProvider.buildResult(requestEntity, sae.getCode(), sae.getMessage()));
			}
			if (parent instanceof MagicScriptException) {
				se = (MagicScriptException) parent;
			}
		} while ((parent = parent.getCause()) != null);
		logger.error("测试脚本出错", root);
		if (se != null) {
			Span.Line line = se.getLine();
			return new JsonBodyBean<>(-1000, se.getSimpleMessage(), resultProvider.buildException(requestEntity, se), line == null ? null : Arrays.asList(line.getLineNumber(), line.getEndLineNumber(), line.getStartCol(), line.getEndCol()));
		}
		return new JsonBean<>(-1, root.getMessage(), resultProvider.buildException(requestEntity, root));
	}

	/**
	 * 初始化DEBUG
	 */
	private MagicScriptDebugContext initializeDebug(RequestEntity requestEntity) {
		MagicScriptDebugContext context = (MagicScriptDebugContext) requestEntity.getMagicScriptContext();
		HttpServletRequest request = requestEntity.getRequest();
		// 由于debug是开启一个新线程，为了防止在子线程中无法获取request对象，所以将request放在InheritableThreadLocal中。
		RequestContextHolder.setRequestAttributes(RequestContextHolder.getRequestAttributes(), true);

		String sessionId = requestEntity.getRequestedSessionId();
		// 设置断点
		context.setBreakpoints(requestEntity.getRequestedBreakpoints());
		context.setTimeout(configuration.getDebugTimeout());
		context.setId(sessionId);
		// 设置相关回调，打印日志，回收资源
		context.onComplete(() -> {
			if (context.isException()) {
				MagicLoggerContext.println(new LogInfo(Level.ERROR.name().toLowerCase(), "执行脚本出错", (Throwable) context.getReturnValue()));
			}
			logger.info("Close Console Session : {}", sessionId);
			RequestContext.remove();
			MagicLoggerContext.remove(sessionId);
		});
		context.onStart(() -> {
			RequestContext.setRequestEntity(requestEntity);
			MagicLoggerContext.SESSION.set(sessionId);
			logger.info("Create Console Session : {}", sessionId);
		});
		return context;
	}

	/**
	 * 判断是否是测试请求
	 */
	private boolean isRequestedFromTest(HttpServletRequest request) {
		return configuration.isEnableWeb() && request.getHeader(HEADER_REQUEST_SESSION) != null;
	}

	/**
	 * 读取RequestBody
	 */
	private Object readRequestBody(HttpServletRequest request) throws IOException {
		if (configuration.getHttpMessageConverters() != null && request.getContentType() != null) {
			MediaType mediaType = MediaType.valueOf(request.getContentType());
			Class clazz = Object.class;
			try {
				for (HttpMessageConverter<?> converter : configuration.getHttpMessageConverters()) {
					if (converter.canRead(clazz, mediaType)) {
						return converter.read(clazz, new ServletServerHttpRequest(request));
					}
				}
			} catch (HttpMessageNotReadableException ignored) {
				return null;
			}
		}
		return null;
	}

	/**
	 * 构建 MagicScriptContext
	 */
	private MagicScriptContext createMagicScriptContext(RequestEntity requestEntity, Object requestBody) throws IOException {
		// 构建脚本上下文
		MagicScriptContext context = requestEntity.isRequestedFromTest() ? new MagicScriptDebugContext() : new MagicScriptContext();
		Object wrap = requestEntity.getApiInfo().getOptionValue(Options.WRAP_REQUEST_PARAMETERS.getValue());
		if (wrap != null && StringUtils.isNotBlank(wrap.toString())) {
			context.set(wrap.toString(), requestEntity.getParameters());
		}
		context.putMapIntoContext(requestEntity.getParameters());
		context.putMapIntoContext(requestEntity.getPathVariables());
		context.set(VAR_NAME_COOKIE, new CookieContext(requestEntity.getRequest()));
		context.set(VAR_NAME_HEADER, requestEntity.getHeaders());
		context.set(VAR_NAME_SESSION, new SessionContext(requestEntity.getRequest().getSession()));
		context.set(VAR_NAME_PATH_VARIABLE, requestEntity.getPathVariables());
		if (requestBody != null) {
			context.set(VAR_NAME_REQUEST_BODY, requestBody);
		}
		return context;
	}

	/**
	 * 包装返回结果
	 */
	private Object response(RequestEntity requestEntity, Object value) {
		if (value instanceof ResponseEntity) {
			return value;
		} else if (value instanceof ResponseModule.NullValue) {
			return null;
		}
		return resultProvider.buildResult(requestEntity, value);
	}

	/**
	 * 执行后置拦截器
	 */
	private Object doPostHandle(RequestEntity requestEntity, Object value) throws Exception {
		for (RequestInterceptor requestInterceptor : configuration.getRequestInterceptors()) {
			Object target = requestInterceptor.postHandle(requestEntity, value);
			if (target != null) {
				return target;
			}
		}
		return null;
	}

	/**
	 * 执行前置拦截器
	 */
	private Object doPreHandle(RequestEntity requestEntity) throws Exception {
		try {
			for (RequestInterceptor requestInterceptor : configuration.getRequestInterceptors()) {
				Object value = requestInterceptor.preHandle(requestEntity);
				if (value != null) {
					return value;
				}
			}
		} catch (Exception e) {
			if (requestEntity.isRequestedFromTest()) {
				// 修正前端显示，原样输出显示
				requestEntity.getResponse().setHeader(HEADER_RESPONSE_WITH_MAGIC_API, CONST_STRING_FALSE);
			}
			throw e;
		}
		return null;
	}

}
