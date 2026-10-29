package io.github.sudoitir.rahdar.kernel.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The API document names a schema by its class's simple name, so two different records with one name merge into a
 * single schema and one endpoint publishes the other's shape. Every type an endpoint takes or returns, and every type
 * reachable from them, has a name of its own.
 */
class ApiSchemaNamesTest extends PostgresIntegrationTest {

    private static final String BASE = "io.github.sudoitir.rahdar.";

    @Autowired
    @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    @Test
    void noTwoApiTypesShareASchemaName() {
        Map<String, Set<Class<?>>> byName = new LinkedHashMap<>();
        Set<Class<?>> seen = new HashSet<>();
        for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
            Method method = handler.getMethod();
            collect(ResolvableType.forMethodReturnType(method, handler.getBeanType()), byName, seen);
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    collect(ResolvableType.forMethodParameter(parameter), byName, seen);
                }
            }
        }
        List<String> clashes = new ArrayList<>();
        byName.forEach((name, types) -> {
            if (types.size() > 1) {
                clashes.add(name + " = "
                        + new TreeSet<>(types.stream().map(Class::getName).toList()));
            }
        });

        assertThat(byName).as("the scan sees the API's types").hasSizeGreaterThan(100);
        assertThat(clashes).as("two API types with one schema name: rename one").isEmpty();
    }

    private static void collect(ResolvableType type, Map<String, Set<Class<?>>> byName, Set<Class<?>> seen) {
        for (ResolvableType generic : type.getGenerics()) {
            collect(generic, byName, seen);
        }
        Class<?> raw = type.resolve();
        if (raw == null) {
            return;
        }
        if (raw.isArray()) {
            collect(ResolvableType.forClass(raw.getComponentType()), byName, seen);
            return;
        }
        if (!raw.getName().startsWith(BASE) || !seen.add(raw)) {
            return;
        }
        byName.computeIfAbsent(raw.getSimpleName(), n -> new HashSet<>()).add(raw);
        if (raw.isRecord()) {
            for (RecordComponent component : raw.getRecordComponents()) {
                collect(ResolvableType.forType(component.getGenericType(), type), byName, seen);
            }
        }
    }
}
