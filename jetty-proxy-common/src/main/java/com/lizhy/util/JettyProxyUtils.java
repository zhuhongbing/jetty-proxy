package com.lizhy.util;

import com.alibaba.fastjson.JSON;
import com.lizhy.constants.ProjectConstants;
import com.lizhy.model.JettyProxyContext;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by lizhiyang on 2017-04-26 14:55.
 */
public class JettyProxyUtils {
    private static Logger logger = LoggerFactory.getLogger(JettyProxyUtils.class);
    private static Pattern p =  Pattern.compile("(?<=//|)((\\w)+\\.)+\\w+");

    private static final Set<String> HOP_HEADERS;
    static
    {
        Set<String> hopHeaders = new HashSet<>();
        hopHeaders.add("connection");
        hopHeaders.add("keep-alive");
        hopHeaders.add("proxy-authorization");
        hopHeaders.add("proxy-authenticate");
        hopHeaders.add("proxy-connection");
        hopHeaders.add("transfer-encoding");
        hopHeaders.add("te");
        hopHeaders.add("trailer");
        hopHeaders.add("upgrade");
        HOP_HEADERS = Collections.unmodifiableSet(hopHeaders);
    }

    /**
     * 填充JettyProxyContext信息
     * @param request
     * @return
     */
    public static JettyProxyContext setJettyProxyContext(HttpServletRequest request) {
        JettyProxyContext context = new JettyProxyContext();
        setRequestParams(request, context);
        setRequestHeader(request, context);
        String charset = request.getCharacterEncoding();
        charset = charset == null ? "UTF-8" : charset;
        context.setCharset(charset);
        context.setRequestIP(ToolUtils.getRemoteIPAddress(request));
        context.setRequestURI(request.getRequestURI());
        return context;
    }

    /**
     * 获取请求参数
     * @param request
     * @param requestContext
     */
    private static void setRequestParams(HttpServletRequest request,JettyProxyContext context){
        String queryString = request.getQueryString();
        if(StringUtils.isBlank(queryString)) {
            context.setBodyParameterMap(request.getParameterMap());
        } else {
            //url后存在参数，区分出body体中的参数
            Map<String, String[]> bodyParamMap = new HashMap<String, String[]>();
            Map<String, String[]> urlParamMap = ParamUtils.getUrlParamMap(queryString);
            if(!ToolUtils.isMapEmpty(urlParamMap)) {
                context.setDispatcherUrl(ParamUtils.getValueFromMap(urlParamMap, ProjectConstants.JETTY_DISPATCHER_URL));
                urlParamMap.remove(ProjectConstants.JETTY_DISPATCHER_URL);
                context.setUrlParameterMap(urlParamMap);
            }
            Map<String, String[]> paramMap = request.getParameterMap();
            logger.debug("request parameterMap:"+paramMap);
            if(!ToolUtils.isMapEmpty(urlParamMap) && !ToolUtils.isMapEmpty(paramMap)) {
                for(String paramKey : paramMap.keySet()) {
                    String[] paramValue = paramMap.get(paramKey);
                    if(paramValue != null && paramValue.length > 0) {
                        int len = paramValue.length;
                        if(urlParamMap.containsKey(paramKey)) {
                            if(len > 1) {
                                for(String value : paramValue) {
                                    if(!ParamUtils.hasValueOfMap(urlParamMap, paramKey, value)) {
                                        ParamUtils.put2ArrIfExist(bodyParamMap, paramKey, value);
                                    }
                                }
                            }
                        } else {
                            for(String value : paramValue) {
                                ParamUtils.put2ArrIfExist(bodyParamMap, paramKey, value);
                            }
                        }
                    } else {
                        bodyParamMap.put(paramKey, new String[]{""});
                    }
                }
                context.setBodyParameterMap(bodyParamMap);
            }
        }
        Map<String, String[]> paramMap = ParamUtils.combinationParamMap(context.getUrlParameterMap(), context.getBodyParameterMap());
        if(logger.isDebugEnabled()) {
            logger.debug("urlParameterMap:"+ JSON.toJSONString(context.getUrlParameterMap()));
            logger.debug("bodyParameterMap:"+JSON.toJSONString(context.getBodyParameterMap()));
            logger.debug("combinationMap:"+ JSON.toJSONString(paramMap));
        }
        context.setParameterMap(paramMap);
    }

    /**
     * 获取请求头信息
     * @param request
     * @param requestContext
     */
    private static void setRequestHeader(HttpServletRequest request,JettyProxyContext contextt){

    }

    /**
     * 填充Jetty转发Request信息
     * @param request
     * @param context
     */
    public static void setJettyProxyRequest(Request request, JettyProxyContext context) {
        ByteArrayOutputStream bos = null;
        try {
            if(context.getRequest().getContentLength() > 0) {
                if(context.getRequest().getInputStream() != null) {
                    bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len = 0;
                    while((len = context.getRequest().getInputStream().read(buffer)) > -1) {
                        bos.write(buffer, 0, len);
                    }
                    bos.flush();
                    byte[] content = bos.toByteArray();
                    if(content != null && content.length > 0) {
                        request.content(new InputStreamContentProvider(new ByteArrayInputStream(content), context.getRequest().getContentLength()),
                                context.getRequest().getContentType());
                    }
                }
                if(!ToolUtils.isMapEmpty(context.getBodyParameterMap())) {
                    String bodyParam = ParamUtils.getFormEncodedString(context.getBodyParameterMap(), context.getCharset());
                    request.content(new StringContentProvider(bodyParam, context.getCharset()),
                            context.getRequest().getContentType());
                }
            }

        } catch (IOException e) {
            logger.error("request content exception", e);
        } finally {
            if(bos != null) {
                try {
                    bos.close();
                } catch (IOException e) {
                    logger.error("stream close exception", e);
                }
            }
        }

        context.setSendTime(System.currentTimeMillis());
        // 转发headerMap
        copyRequestHeaders(context.getRequest(), request);
        request.header(HttpHeader.HOST, getHost(context.getDispatcherUrl()));
        addXForwardedHeaders(context.getRequest(), request);
    }

    private static String getHost(String url){
        if(url==null||url.trim().equals("")){
            return "";
        }
        String host = "";
        Matcher matcher = p.matcher(url);
        if(matcher.find()){
            host = matcher.group();
        }
        return host;
    }

    private static void copyRequestHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
        // First clear possibly existing headers, as we are going to copy those from the client request.
        proxyRequest.getHeaders().clear();
        Set<String> headersToRemove = findConnectionHeaders(clientRequest);
        for (Enumeration<String> headerNames = clientRequest.getHeaderNames(); headerNames.hasMoreElements();) {
            String headerName = headerNames.nextElement();
            String lowerHeaderName = headerName.toLowerCase(Locale.ENGLISH);

            if (HttpHeader.HOST.is(headerName)) {
                continue;
            }
            // Remove hop-by-hop headers.
            if (HOP_HEADERS.contains(lowerHeaderName)) {
                continue;
            }
            if (headersToRemove != null && headersToRemove.contains(lowerHeaderName)) {
                continue;
            }
            for (Enumeration<String> headerValues = clientRequest.getHeaders(headerName); headerValues.hasMoreElements();) {
                String headerValue = headerValues.nextElement();
                if (headerValue != null) {
                    proxyRequest.header(headerName, headerValue);
                }
            }
        }
    }

    private static Set<String> findConnectionHeaders(HttpServletRequest clientRequest) {
        Set<String> hopHeaders = null;
        Enumeration<String> connectionHeaders = clientRequest.getHeaders(HttpHeader.CONNECTION.asString());
        while (connectionHeaders.hasMoreElements()) {
            String value = connectionHeaders.nextElement();
            String[] values = value.split(",");
            for (String name : values) {
                name = name.trim().toLowerCase(Locale.ENGLISH);
                if (hopHeaders == null) {
                    hopHeaders = new HashSet<>();
                }
                hopHeaders.add(name);
            }
        }
        return hopHeaders;
    }

    private static void addXForwardedHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
        proxyRequest.header(HttpHeader.X_FORWARDED_FOR, clientRequest.getRemoteAddr());
        proxyRequest.header(HttpHeader.X_FORWARDED_PROTO, clientRequest.getScheme());
        proxyRequest.header(HttpHeader.X_FORWARDED_HOST, clientRequest.getHeader(HttpHeader.HOST.asString()));
        proxyRequest.header(HttpHeader.X_FORWARDED_SERVER, clientRequest.getLocalName());
    }

    /**
     * 拷贝response响应头
     */
    public static void copyResponseHeaders(Response response, HttpServletResponse httpServletResponse) {
        Iterator<HttpField> it = response.getHeaders().iterator();
        while(it.hasNext()){
            HttpField next = it.next();
            String headerName = next.getName();
            String lowerHeaderName = headerName.toLowerCase(Locale.ENGLISH);
            if(!HOP_HEADERS.contains(lowerHeaderName) && !"content-encoding".equals(lowerHeaderName)) {
                httpServletResponse.addHeader(headerName, next.getValue());
            }
        }
    }

    /**
     * 向客户端响应信息
     * @param response
     * @param responseMsg 响应信息
     * @param contentType 响应信息的格式，不填默认为application/json; charset=utf-8
     * @param logger 打印日志使用，如果为空不打印
     */
    public static void handleResponse(HttpServletResponse response,String responseMsg,String contentType,Logger logger){
        handleResponse(response,responseMsg,contentType,200,logger);
    }
    /**
     * 向客户端响应信息
     * @param response
     * @param responseMsg 响应信息
     * @param contentType 响应信息的格式，不填默认为application/json; charset=utf-8
     * @param sc 返回码status code 默认为200
     * @param logger 打印日志使用，如果为空不打印
     */
    public static void handleResponse(HttpServletResponse response,String responseMsg,String contentType,int sc,Logger logger) {
        OutputStream out = null;
        try {
            response.setCharacterEncoding("UTF-8");
            if(StringUtils.isBlank(contentType)) {
                contentType = "application/json; charset=utf-8";
            }
            response.setContentType(contentType);
            if(sc <= 0) {
                sc = HttpServletResponse.SC_OK;
            }
            response.setStatus(sc);
            out = response.getOutputStream();
            out.write(responseMsg.getBytes("UTF-8"));
        } catch (IOException e) {
            if(logger != null) {
                logger.error("handleResponse error, responseMsg" + responseMsg, e);
            }
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    logger.error("outputstream close exception", e);
                }
            }
        }
    }


}
