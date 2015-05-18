package org.visallo.web;

import com.google.inject.Injector;
import org.visallo.core.config.Configuration;
import org.visallo.core.config.VisalloResourceBundleManager;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import com.v5analytics.webster.App;
import com.v5analytics.webster.Handler;
import com.v5analytics.webster.handlers.AppendableStaticResourceHandler;
import com.v5analytics.webster.handlers.StaticResourceHandler;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.vertexium.util.CloseableUtils.closeQuietly;

public class WebApp extends App {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(WebApp.class);
    private final Injector injector;
    private final boolean devMode;
    private final AppendableStaticResourceHandler pluginsJsResourceHandler = new No404AppendableStaticResourceHandler("application/javascript");
    private final List<String> pluginsJsResources = new ArrayList<String>();
    private final AppendableStaticResourceHandler pluginsWebWorkerJsResourceHandler = new No404AppendableStaticResourceHandler("application/javascript");
    private final List<String> pluginsWebWorkerJsResources = new ArrayList<String>();
    private final AppendableStaticResourceHandler pluginsCssResourceHandler = new No404AppendableStaticResourceHandler("text/css");
    private final List<String> pluginsCssResources = new ArrayList<String>();
    private VisalloResourceBundleManager visalloResourceBundleManager = new VisalloResourceBundleManager();

    public WebApp(final ServletContext servletContext, final Injector injector) {
        super(servletContext);
        this.injector = injector;

        Configuration config = injector.getInstance(Configuration.class);
        this.devMode = "true".equals(config.get(Configuration.DEV_MODE, "false"));

        if (!devMode) {
            String pluginsJsRoute = "plugins.js";
            this.get("/" + pluginsJsRoute, pluginsJsResourceHandler);
            pluginsJsResources.add(pluginsJsRoute);

            String pluginsCssRoute = "plugins.css";
            this.get("/" + pluginsCssRoute, pluginsCssResourceHandler);
            pluginsCssResources.add(pluginsCssRoute);
        }

        String pluginsWebWorkerJsRoute = "plugins-web-worker.js";
        this.get("/" + pluginsWebWorkerJsRoute, pluginsWebWorkerJsResourceHandler);
        pluginsWebWorkerJsResources.add(pluginsWebWorkerJsRoute);
    }

    @Override
    protected Handler[] instantiateHandlers(Class<? extends Handler>[] handlerClasses) throws Exception {
        Handler[] handlers = new Handler[handlerClasses.length];
        for (int i = 0; i < handlerClasses.length; i++) {
            handlers[i] = injector.getInstance(handlerClasses[i]);
        }
        return handlers;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (request.getRequestURI().endsWith("ejs")) {
            response.setContentType("text/plain");
        }
        response.setCharacterEncoding("UTF-8");
        super.handle(request, response);
    }

    private void register(String name, String type, String pathPrefix, Boolean includeInPage) {
        String resourcePath = pathPrefix + name;
        if (devMode || !includeInPage) {
            get("/" + resourcePath, new StaticResourceHandler(this.getClass(), name, type));
            if (includeInPage) {
                pluginsJsResources.add(resourcePath);
            }
        } else {
            if (includeInPage) {
                pluginsJsResourceHandler.appendResource(name);
            }
        }
    }

    public void registerJavaScript(String scriptResourceName, Boolean includeInPage) {
        register(scriptResourceName, "application/javascript", "jsc", includeInPage);
    }

    public void registerJavaScript(String scriptResourceName) {
        registerJavaScript(scriptResourceName, true);
    }

    public void registerWebWorkerJavaScript(String scriptResourceName) {
        pluginsWebWorkerJsResourceHandler.appendResource(scriptResourceName);
    }

    public void registerJavaScriptTemplate(String scriptResourceName) {
        register(scriptResourceName, "text/plain", "jsc", false);
    }

    public void registerCss(String cssResourceName) {
        String resourcePath = "css" + cssResourceName;
        if (devMode) {
            get("/" + resourcePath, new StaticResourceHandler(this.getClass(), cssResourceName, "text/css"));
            pluginsCssResources.add(resourcePath);
        } else {
            pluginsCssResourceHandler.appendResource(cssResourceName);
        }
    }

    public static Locale getLocal(String language, String country, String variant) {
        if (language != null) {
            if (country != null) {
                if (variant != null) {
                    return new Locale(language, country, variant);
                }
                return new Locale(language, country);
            }
            return new Locale(language);
        }
        return Locale.getDefault();
    }

    public void registerResourceBundle(String resourceBundleResourceName) {
        InputStream stream = WebApp.class.getResourceAsStream(resourceBundleResourceName);
        if (stream == null) {
            throw new VisalloException("Could not find resource bundle resource: " + resourceBundleResourceName);
        }
        try {
            Pattern pattern = Pattern.compile(".*_([a-z]{2})(?:_([A-Z]{2}))?(?:_(.+))?\\.properties");
            Matcher matcher = pattern.matcher(resourceBundleResourceName);
            if (matcher.matches()) {
                String language = matcher.group(1);
                String country = matcher.group(2);
                String variant = matcher.group(3);
                Locale locale = getLocal(language, country, variant);
                LOGGER.info("registering ResourceBundle plugin file: %s with locale: %s", resourceBundleResourceName, locale);
                visalloResourceBundleManager.register(stream, locale);
            } else {
                LOGGER.info("registering ResourceBundle plugin file: %s", resourceBundleResourceName);
                visalloResourceBundleManager.register(stream);
            }
        } catch (IOException e) {
            throw new VisalloException("Could not read resource bundle resource: " + resourceBundleResourceName);
        } finally {
            closeQuietly(stream);
        }
    }

    public ResourceBundle getBundle(Locale locale) {
        return visalloResourceBundleManager.getBundle(locale);
    }

    public List<String> getPluginsJsResources() {
        return pluginsJsResources;
    }

    public List<String> getPluginsCssResources() {
        return pluginsCssResources;
    }

    public boolean isDevModeEnabled() {
        return devMode;
    }
}