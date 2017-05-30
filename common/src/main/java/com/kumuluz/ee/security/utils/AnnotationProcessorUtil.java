/*
 *  Copyright (c) 2014-2017 Kumuluz and/or its affiliates
 *  and other contributors as indicated by the @author tags and
 *  the contributor list.
 *
 *  Licensed under the MIT License (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://opensource.org/licenses/MIT
 *
 *  The software is provided "AS IS", WITHOUT WARRANTY OF ANY KIND, express or
 *  implied, including but not limited to the warranties of merchantability,
 *  fitness for a particular purpose and noninfringement. in no event shall the
 *  authors or copyright holders be liable for any claim, damages or other
 *  liability, whether in an action of contract, tort or otherwise, arising from,
 *  out of or in connection with the software or the use or other dealings in the
 *  software. See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.kumuluz.ee.security.utils;

import com.kumuluz.ee.configuration.utils.ConfigurationUtil;
import com.kumuluz.ee.security.annotations.Keycloak;
import com.kumuluz.ee.security.models.SecurityConstraint;
import org.json.JSONException;
import org.json.JSONObject;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.DenyAll;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.core.Application;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

/**
 * Interceptor class for DeclareRoles annotation.
 *
 * @author Benjamin Kastelic
 */
@ApplicationScoped
public class AnnotationProcessorUtil {

    private static final Logger log = Logger.getLogger(AnnotationProcessorUtil.class.getName());

    @Inject
    private SecurityConfigurationUtil securityConfigurationUtil;

    public void init(@Observes @Initialized(ApplicationScoped.class) Object context) {
        List<Application> applications = new ArrayList<>();
        ServiceLoader.load(Application.class).forEach(applications::add);
        for (Application application : applications) {
            List<String> declaredRoles = getDeclaredRoles(application.getClass());
            List<SecurityConstraint> constraints = getConstraints(application.getClass());
            configureSecurity(application.getClass(), context, declaredRoles, constraints);
        }
    }

    private void configureSecurity(Class targetClass, Object context, List<String> declaredRoles, List<SecurityConstraint> constraints) {
        ConfigurationUtil configurationUtil = ConfigurationUtil.getInstance();

        if (targetClassIsProxied(targetClass)) {
            targetClass = targetClass.getSuperclass();
        }

        Keycloak keycloakAnnotation = (Keycloak) targetClass.getAnnotation(Keycloak.class);

        String jsonString = "";
        JSONObject json;
        String authServerUrl = "";
        String sslRequired = "";

        if (keycloakAnnotation != null) {
            jsonString = keycloakAnnotation.json();
            authServerUrl = keycloakAnnotation.authServerUrl();
            sslRequired = keycloakAnnotation.sslRequired();
        }

        if (jsonString.isEmpty()) {
            jsonString = configurationUtil.get("kumuluzee.security.keycloak.json").orElse("{}");
            json = toJSONObject(jsonString);
        } else {
            json = toJSONObject(jsonString);
        }

        if (!authServerUrl.isEmpty()) {
            json.put("auth-server-url", authServerUrl);
        }

        if (!sslRequired.isEmpty()) {
            json.put("ssl-required", sslRequired);
        }

        securityConfigurationUtil.configureSecurity(json.toString(), context, declaredRoles, constraints);
    }

    private List<String> getDeclaredRoles(Class<?> applicationClass) {
        if (targetClassIsProxied(applicationClass)) {
            applicationClass = applicationClass.getSuperclass();
        }

        Set<String> declaredRoles = new HashSet<>();

        DeclareRoles[] annotations = applicationClass.getAnnotationsByType(DeclareRoles.class);
        Arrays.asList(annotations).forEach(annotation -> {
            declaredRoles.addAll(Arrays.asList(annotation.value()));
        });

        return new ArrayList<>(declaredRoles);
    }

    private List<SecurityConstraint> getConstraints(Class<?> applicationClass) {
        if (targetClassIsProxied(applicationClass)) {
            applicationClass = applicationClass.getSuperclass();
        }

        List<SecurityConstraint> constraints = new ArrayList<>();

        String applicationPath = "";
        ApplicationPath applicationPathAnnotation = applicationClass.getAnnotation(ApplicationPath.class);
        if (applicationPathAnnotation != null) {
            applicationPath = applicationPathAnnotation.value();
            if (!applicationPath.isEmpty() && !applicationPath.startsWith("/")) {
                applicationPath = "/" + applicationPath;
            }
        }

        List<Class<?>> resourceClasses = getResourceClasses(applicationClass);
        for (Class<?> resourceClass : resourceClasses) {
            if (targetClassIsProxied(resourceClass)) {
                resourceClass = resourceClass.getSuperclass();
            }

            String resourcePath = "";
            Path resourcePathAnnotation = resourceClass.getAnnotation(Path.class);
            if (resourcePathAnnotation != null) {
                resourcePath = resourcePathAnnotation.value();
                if (!resourcePath.isEmpty() && !resourcePath.startsWith("/")) {
                    resourcePath = applicationPath + "/" + resourcePath;
                } else if (!resourcePath.isEmpty() && resourcePath.startsWith("/")) {
                    resourcePath = applicationPath + resourcePath;
                } else {
                    resourcePath = applicationPath;
                }
            }

            boolean resourceDenyAll = resourceClass.getAnnotation(DenyAll.class) != null;
            List<String> resourceRolesAllowed = resourceClass.getAnnotation(RolesAllowed.class) != null
                    ? Arrays.asList(resourceClass.getAnnotation(RolesAllowed.class).value())
                    : null;
            boolean resourcePermitAll = resourceClass.getAnnotation(PermitAll.class) != null;
            boolean hasResourceSecurityAnnotations = resourceDenyAll || resourceRolesAllowed != null || resourcePermitAll;

            for (Method resourceMethod : Arrays.asList(resourceClass.getMethods())) {
                boolean hasMethodSecurityAnnotations = false;
                String methodHttpMethod = null;
                boolean methodPathPresent = false;
                String methodPath = resourcePath;
                List<String> methodRolesAllowed = null;
                boolean methodDenyAll = false, methodPermitAll = false;

                for (Annotation methodAnnotation : Arrays.asList(resourceMethod.getAnnotations())) {
                    if (methodAnnotation instanceof Path) {
                        methodPathPresent = true;
                        Path methodPathAnnotation = (Path) methodAnnotation;
                        methodPath = methodPathAnnotation.value();
                        if (!methodPath.isEmpty()) {
                            if (methodPath.startsWith("/")) {
                                methodPath = resourcePath + methodPath;
                            } else if (!methodPath.startsWith("/")) {
                                methodPath = resourcePath + "/" + methodPath;
                            } else {
                                methodPath = resourcePath;
                            }
                        } else {
                            methodPath = resourcePath;
                        }
                        methodPath = replaceParameters(methodPath);
                    } else if (methodAnnotation instanceof DenyAll) {
                        hasMethodSecurityAnnotations = true;
                        methodDenyAll = true;
                    } else if (methodAnnotation instanceof RolesAllowed) {
                        hasMethodSecurityAnnotations = true;
                        RolesAllowed methodRolesAllowedAnnotation = (RolesAllowed) methodAnnotation;
                        methodRolesAllowed = Arrays.asList(methodRolesAllowedAnnotation.value());
                    } else if (methodAnnotation instanceof PermitAll) {
                        hasMethodSecurityAnnotations = true;
                        methodPermitAll = true;
                    } else if (methodAnnotation.annotationType().getAnnotation(HttpMethod.class) != null) {
                        HttpMethod methodHttpMethodAnnotation = methodAnnotation.annotationType().getAnnotation(HttpMethod.class);
                        methodHttpMethod = methodHttpMethodAnnotation.value();
                    }
                }

                if (methodHttpMethod == null && methodPathPresent) {
                    methodHttpMethod = "GET";
                }

                if (methodHttpMethod == null) {
                    continue;
                }

                SecurityConstraint constraint = null;
                if (hasMethodSecurityAnnotations) {
                    if (methodDenyAll || (methodRolesAllowed != null && !methodRolesAllowed.isEmpty())) {
                        constraint = new SecurityConstraint(methodHttpMethod, methodPath, methodRolesAllowed);
                    } else {
                        constraint = new SecurityConstraint(methodHttpMethod, methodPath);
                    }
                } else if (hasResourceSecurityAnnotations) {
                    if (resourceDenyAll || (resourceRolesAllowed != null && !resourceRolesAllowed.isEmpty())) {
                        constraint = new SecurityConstraint(methodHttpMethod, methodPath, resourceRolesAllowed);
                    } else {
                        constraint = new SecurityConstraint(methodHttpMethod, methodPath);
                    }
                }

                if (constraint != null) {
                    constraints.add(constraint);
                }
            }
        }

        return constraints;
    }

    private String replaceParameters(String path) {
        return path.replaceAll("\\{.*", "*");
    }

    private JSONObject toJSONObject(String jsonString) {
        JSONObject json;
        try {
            json = new JSONObject(jsonString);
        } catch (JSONException e) {
            json = new JSONObject();
        }
        return json;
    }

    private List<Class<?>> getResourceClasses(Class applicationClass) {
        List<Class<?>> resourceClasses = new ArrayList<>();

        ClassLoader classLoader = getClass().getClassLoader();
        URL fileUrl = classLoader.getResource("META-INF/resources/java.lang.Object");
        if (fileUrl != null) {
            File file = new File(fileUrl.getFile());

            try (Scanner scanner = new Scanner(file)) {
                while (scanner.hasNextLine()) {
                    String className = scanner.nextLine();
                    if (className.startsWith(applicationClass.getPackage().getName())) {
                        try {
                            Class resourceClass = Class.forName(className);
                            resourceClasses.add(resourceClass);
                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                }
                scanner.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return resourceClasses;
    }

    /**
     * Check if target class is proxied.
     *
     * @param targetClass target class
     * @return true if target class is proxied
     */
    private boolean targetClassIsProxied(Class targetClass) {
        return targetClass.getCanonicalName().contains("$Proxy");
    }
}
