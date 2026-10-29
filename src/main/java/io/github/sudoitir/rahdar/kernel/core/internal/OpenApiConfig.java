package io.github.sudoitir.rahdar.kernel.core.internal;

import io.github.sudoitir.rahdar.kernel.core.Branding;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the OpenAPI document's {@code info} (its version is the running Rahdar version) and drops the generated server list so
 * {@code web/openapi.json} (ADR-0019) is a stable snapshot — it must not change
 * just because the test ran on a different host or port.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI rahdarOpenApi(BuildProperties build) {
        return new OpenAPI()
                .info(new Info()
                        .title(Branding.PRODUCT_NAME + " API")
                        .description(Branding.TAGLINE)
                        .version(build.getVersion()))
                .servers(List.of());
    }

    /**
     * A primitive property is required in the contract (ADR-0096): Jackson 3 rejects a request that
     * omits one ({@code FAIL_ON_NULL_FOR_PRIMITIVES}), and a response always carries one.
     */
    @Bean
    PropertyCustomizer primitivesAreRequired() {
        return (property, type) -> {
            if (type.getParent() != null
                    && type.getPropertyName() != null
                    && Json.mapper().constructType(type.getType()).isPrimitive()) {
                List<String> required = type.getParent().getRequired();
                if (required == null || !required.contains(type.getPropertyName())) {
                    type.getParent().addRequiredItem(type.getPropertyName());
                }
            }
            return property;
        };
    }

    /**
     * Every operation can fail, and every failure is a problem (api-contract spec): {@code 4XX} and
     * {@code 5XX} carry {@code application/problem+json}, and 429 names the headers a client backs off by.
     * An operation that documents one of these itself keeps its own.
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            openApi.getComponents().addSchemas("ProblemDetail", problemSchema());
            Content problem = new Content()
                    .addMediaType(
                            org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail")));
            ApiResponse tooMany = new ApiResponse()
                    .description("Too many requests. Wait for Retry-After seconds.")
                    .content(problem)
                    .addHeaderObject("Retry-After", header("Seconds to wait before retrying."))
                    .addHeaderObject("RateLimit-Limit", header("Requests allowed in the window (API tokens)."))
                    .addHeaderObject("RateLimit-Remaining", header("Requests left in the window (API tokens)."))
                    .addHeaderObject("RateLimit-Reset", header("Seconds until the window resets (API tokens)."));
            openApi.getPaths()
                    .values()
                    .forEach(path -> path.readOperations().forEach(op -> {
                        op.getResponses()
                                .putIfAbsent(
                                        "4XX",
                                        new ApiResponse()
                                                .description("Client error")
                                                .content(problem));
                        op.getResponses()
                                .putIfAbsent(
                                        "5XX",
                                        new ApiResponse()
                                                .description("Server error")
                                                .content(problem));
                        op.getResponses().putIfAbsent("429", tooMany);
                    }));
        };
    }

    /**
     * A nullable reference is "the object or null", and a nullable enum lists {@code null}: springdoc renders the
     * first as {@code type: null} beside the {@code $ref} (nothing can satisfy that) and the second without the
     * {@code null} value, so a client generated from the document would reject a response the API sends.
     */
    @Bean
    OpenApiCustomizer nullableReferencesAndEnums() {
        return openApi -> {
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                openApi.getComponents().getSchemas().values().forEach(OpenApiConfig::fixNullable);
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void fixNullable(Schema schema) {
        if (schema == null) {
            return;
        }
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null) {
            properties.replaceAll((name, property) -> {
                fixNullable(property);
                boolean nullable =
                        property.getTypes() != null && property.getTypes().contains("null");
                if (nullable && property.get$ref() != null) {
                    return new Schema<>()
                            .anyOf(List.of(
                                    new Schema<>().$ref(property.get$ref()), new Schema<>().types(Set.of("null"))));
                }
                if (nullable
                        && property.getEnum() != null
                        && !property.getEnum().contains(null)) {
                    List<Object> values = new ArrayList<>(property.getEnum());
                    values.add(null);
                    property.setEnum(values);
                }
                return property;
            });
        }
        fixNullable(schema.getItems());
        if (schema.getAdditionalProperties() instanceof Schema additional) {
            fixNullable(additional);
        }
    }

    /**
     * A union keyed by a discriminator ({@code oneOf} with a discriminator) lists members that each say they are the
     * union too ({@code allOf} of the union), which is a cycle: openapi-generator makes every member extend the union
     * and override the other members' properties, and the Java client does not compile. A member stands alone here,
     * with its own properties and required list, and the union keeps only its {@code oneOf} and discriminator.
     */
    @Bean
    OpenApiCustomizer unionMembersStandAlone() {
        return openApi -> {
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            new ArrayList<>(schemas.entrySet()).forEach(union -> {
                Schema<?> parent = union.getValue();
                if (parent.getOneOf() == null || parent.getDiscriminator() == null) {
                    return;
                }
                String parentRef = "#/components/schemas/" + union.getKey();
                for (Object member : parent.getOneOf()) {
                    String ref = ((Schema<?>) member).get$ref();
                    Schema<?> child = ref == null ? null : schemas.get(ref.substring(ref.lastIndexOf('/') + 1));
                    if (child == null || child.getAllOf() == null) {
                        continue;
                    }
                    Schema<?> flat = new ObjectSchema();
                    for (Object part : child.getAllOf()) {
                        Schema<?> piece = (Schema<?>) part;
                        if (!parentRef.equals(piece.get$ref()) && piece.getProperties() != null) {
                            piece.getProperties().forEach(flat::addProperty);
                        }
                    }
                    if (child.getRequired() != null) {
                        child.getRequired().forEach(flat::addRequiredItem);
                    }
                    flat.setDescription(child.getDescription());
                    schemas.put(ref.substring(ref.lastIndexOf('/') + 1), flat);
                }
                parent.setProperties(null);
                parent.setRequired(null);
            });
        };
    }

    /**
     * Every {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} accepts an optional
     * {@code Idempotency-Key} (ADR-0150). The refusals it adds are problems, covered by {@code 4XX}.
     */
    @Bean
    OpenApiCustomizer idempotencyKey() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/")) {
                return;
            }
            item.readOperationsMap().forEach((method, op) -> {
                if (method != PathItem.HttpMethod.GET
                        && method != PathItem.HttpMethod.HEAD
                        && method != PathItem.HttpMethod.OPTIONS
                        && method != PathItem.HttpMethod.TRACE) {
                    op.addParametersItem(new HeaderParameter()
                            .name("Idempotency-Key")
                            .required(false)
                            .description("Makes the request safe to retry. Within 24 hours a repeat by the same user"
                                    + " with the same method, path, query and body returns the first result with"
                                    + " `Idempotent-Replayed: true` and applies nothing again. The same key with"
                                    + " another request is 422 `idempotency-key-reused`; while the first is still"
                                    + " running it is 409 `idempotency-in-progress`. 1 to 255 printable ASCII"
                                    + " characters; not accepted on multipart uploads. Server errors and 401, 403"
                                    + " and 429 are not recorded, so the retry runs.")
                            .schema(new StringSchema().minLength(1).maxLength(255)));
                }
            });
        });
    }

    /**
     * The RFC 9457 body (api-contract spec): the standard members and every extension member an advice
     * sets, all optional. Spring's own schema nests them under {@code properties}, which is not what is sent.
     */
    private static Schema<?> problemSchema() {
        ObjectSchema problem = new ObjectSchema();
        problem.addProperty("type", new StringSchema().format("uri"));
        problem.addProperty("title", new StringSchema());
        problem.addProperty("status", new IntegerSchema().format("int32"));
        problem.addProperty("detail", new StringSchema());
        problem.addProperty("instance", new StringSchema().format("uri"));
        problem.addProperty("requestId", new StringSchema().description("Quote it when reporting a server error."));
        ObjectSchema fieldError = new ObjectSchema();
        fieldError.addProperty("field", new StringSchema());
        fieldError.addProperty("message", new StringSchema());
        problem.addProperty("errors", new ArraySchema().items(fieldError).description("Invalid request fields."));
        problem.addProperty("brokerErrorKind", new StringSchema());
        problem.addProperty(
                "account", new StringSchema().description("MANAGEMENT or CORE: the account a broker rejected."));
        problem.addProperty("refusalKind", new StringSchema());
        problem.addProperty("affectedCount", new IntegerSchema().format("int64"));
        problem.addProperty("cap", new IntegerSchema().format("int64"));
        problem.addProperty("missing", new ArraySchema().items(new StringSchema()));
        problem.addProperty("offending", new StringSchema());
        problem.addProperty("suggestion", new StringSchema());
        problem.addProperty("estimate", new NumberSchema());
        problem.addProperty("ceiling", new NumberSchema());
        problem.addProperty("hint", new StringSchema());
        problem.addProperty("field", new StringSchema());
        problem.addProperty("retryAfter", new IntegerSchema().format("int32").description("Seconds to wait."));
        problem.addProperty("featureId", new StringSchema());
        problem.addProperty("property", new StringSchema());
        problem.addProperty(
                "existingClusterId",
                new StringSchema()
                        .format("uuid")
                        .description("The registered cluster that already holds the brokers."));
        problem.addProperty("existingClusterName", new StringSchema());
        problem.addProperty(
                "overlappingNodes",
                new ArraySchema().items(new StringSchema()).description("That cluster's nodes the request names."));
        return problem;
    }

    /**
     * Controllers map paths without {@code /api/v1}; Spring adds {@code /api/{version}} (see
     * {@link ApiVersioningConfig}). The document keeps concrete {@code /api/v1/...} paths and no
     * {@code version} parameter, and marks the operations {@link ApiDeprecations} declares deprecated.
     */
    @Bean
    OpenApiCustomizer versionedPaths(ObjectProvider<ApiDeprecation> deprecations) {
        return openApi -> {
            Paths concrete = new Paths();
            openApi.getPaths().forEach((path, item) -> {
                item.readOperations().forEach(op -> {
                    if (op.getParameters() != null) {
                        op.getParameters().removeIf(p -> "version".equals(p.getName()) && "path".equals(p.getIn()));
                    }
                });
                concrete.addPathItem(path.replace("/api/{version}/", "/api/v1/"), item);
            });
            openApi.setPaths(concrete);
            List<ApiDeprecation> declared = deprecations.orderedStream().toList();
            concrete.forEach((path, item) -> item.readOperationsMap()
                    .forEach((method, op) -> declared.stream()
                            .filter(d -> path.startsWith("/api/v" + d.version() + "/"))
                            .filter(d -> d.path() == null
                                    || d.path().equals(path)
                                            && d.method().name().equals(method.name()))
                            .findFirst()
                            .ifPresent(d -> {
                                op.setDeprecated(true);
                                op.setDescription((op.getDescription() == null ? "" : op.getDescription() + "\n\n")
                                        + "Deprecated since " + d.deprecated().toLocalDate() + "; sunset "
                                        + d.sunset().toLocalDate() + ".");
                            })));
        };
    }

    private static Header header(String description) {
        return new Header().description(description).schema(new IntegerSchema());
    }
}
