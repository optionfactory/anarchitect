package net.optionfactory.anarchitect.deadcode;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;

public class SpringReachabilityStrategy implements ReachabilityStrategy {

    @Override
    public boolean isSource(JavaMethod method, String basePackage) {
        final JavaClass owner = method.getOwner();
        return method.getName().equals("main")
                || method.isMetaAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
                || method.isMetaAnnotatedWith("org.springframework.web.bind.annotation.ExceptionHandler")
                || method.isMetaAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
                || owner.isMetaAnnotatedWith("org.springframework.context.annotation.Configuration")
                || method.isMetaAnnotatedWith("jakarta.ws.rs.HttpMethod")
                || owner.isAssignableTo("org.keycloak.provider.Provider")
                || owner.isAssignableTo("org.keycloak.provider.ProviderFactory")
                || owner.isAssignableTo("org.keycloak.provider.Spi")
                || owner.isMetaAnnotatedWith("jakarta.xml.bind.annotation.XmlRegistry")
                || overridesExternalMethod(method, basePackage);
    }

    @Override
    public boolean isSink(JavaMethod method, String basePackage) {
        final JavaClass owner = method.getOwner();
        return method.isMetaAnnotatedWith("org.springframework.web.service.annotation.HttpExchange")
                || owner.isMetaAnnotatedWith("org.springframework.web.service.annotation.HttpExchange")
                || owner.isMetaAnnotatedWith("org.springframework.stereotype.Repository");
    }
}
