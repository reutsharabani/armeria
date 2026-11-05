/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * Generates an <a href="https://spec.openapis.org/oas/v3.0.3">OpenAPI 3.0</a> document from a
 * {@link ServiceSpecification}.
 *
 * <p>gRPC, Thrift and annotated services are not handled separately. Each {@link DocServicePlugin}
 * already contributes routes, methods and types, and this class turns that one model into OpenAPI.
 * Streaming RPCs, security schemes and exception HTTP status codes are omitted because the
 * specification does not carry them.
 *
 * <p>A gRPC method is exported as {@code POST} of its route, with the request message as the JSON
 * body when {@link MethodInfo#useParameterAsRoot()} is set. HTTP/JSON transcoding routes keep the
 * HTTP method and path parameters already recorded on the method. A single annotated
 * {@code @RequestObject} with no other parameters is wrapped like a Thrift argument, because both
 * are stored as one unspecified parameter.
 */
final class OpenApiGenerator {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
    private static final Pattern COLON_PARAM = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern BRACE_PARAM = Pattern.compile("\\{([^}/]+)}");
    private static final String SWAGGER_UI_CSS =
            "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui.css";
    private static final String SWAGGER_UI_BUNDLE =
            "https://cdn.jsdelivr.net/npm/swagger-ui-dist@5.17.14/swagger-ui-bundle.js";

    private final ServiceSpecification specification;
    private final Map<String, DescriptionInfo> docStrings;
    private final Map<String, DiscriminatorInfo> polymorphismToBase;

    private OpenApiGenerator(ServiceSpecification specification) {
        this.specification = specification;
        docStrings = specification.docStrings();
        polymorphismToBase = polymorphismToBase(specification);
    }

    static ObjectNode generate(ServiceSpecification specification) {
        requireNonNull(specification, "specification");
        return new OpenApiGenerator(specification).doGenerate();
    }

    /**
     * Returns a Swagger UI page that loads {@code openapi.json} next to itself.
     *
     * <p>The page pulls Swagger UI from jsDelivr so DocService does not vendor the distribution.
     */
    static String swaggerUiHtml() {
        return "<!DOCTYPE html>\n" +
               "<html lang=\"en\">\n" +
               "<head>\n" +
               "<meta charset=\"utf-8\"/>\n" +
               "<title>Swagger UI</title>\n" +
               "<link rel=\"stylesheet\" href=\"" + SWAGGER_UI_CSS + "\"/>\n" +
               "</head>\n" +
               "<body>\n" +
               "<div id=\"swagger-ui\"></div>\n" +
               "<script src=\"" + SWAGGER_UI_BUNDLE + "\"></script>\n" +
               "<script>\n" +
               "window.onload = function () {\n" +
               "  window.ui = SwaggerUIBundle({\n" +
               "    url: 'openapi.json',\n" +
               "    dom_id: '#swagger-ui',\n" +
               "    deepLinking: true,\n" +
               "    presets: [SwaggerUIBundle.presets.apis],\n" +
               "    layout: 'BaseLayout'\n" +
               "  });\n" +
               "};\n" +
               "</script>\n" +
               "</body>\n" +
               "</html>\n";
    }

    private ObjectNode doGenerate() {
        final ObjectNode root = mapper.createObjectNode();
        root.put("openapi", "3.0.3");
        root.set("info", info());
        final ObjectNode server = root.putArray("servers").addObject();
        server.put("url", "/");
        server.put("description", "Relative to the server that hosts DocService.");
        root.set("tags", tags());
        root.set("paths", paths());
        root.putObject("components").set("schemas", schemas());
        return root;
    }

    private ObjectNode info() {
        final ObjectNode info = mapper.createObjectNode();
        final String title;
        if (specification.services().size() == 1) {
            title = specification.services().iterator().next().name();
        } else {
            title = "Armeria services";
        }
        info.put("title", title);
        info.put("version", "0.0.0");
        info.put("description",
                 "Generated from an Armeria ServiceSpecification. " +
                 "Streaming RPCs are shown as one request and one response. " +
                 "Security schemes and exception HTTP status codes are not available.");
        return info;
    }

    private ArrayNode tags() {
        final ArrayNode tags = mapper.createArrayNode();
        for (ServiceInfo service : specification.services()) {
            final ObjectNode tag = tags.addObject();
            tag.put("name", service.name());
            final String description = docString(service.name());
            if (!description.isEmpty()) {
                tag.put("description", description);
            }
        }
        return tags;
    }

    private ObjectNode paths() {
        final ObjectNode paths = mapper.createObjectNode();
        for (ServiceInfo service : specification.services()) {
            for (MethodInfo method : service.methods()) {
                if (method.endpoints().isEmpty()) {
                    addOperation(paths, service, method, null,
                                 new OpenApiRoute('/' + service.name() + '/' + method.name(),
                                                   "No endpoint was declared; this path is synthesized."));
                    continue;
                }
                for (EndpointInfo endpoint : method.endpoints()) {
                    addOperation(paths, service, method, endpoint, openApiRoute(endpoint));
                }
            }
        }
        return paths;
    }

    private void addOperation(ObjectNode paths, ServiceInfo service, MethodInfo method,
                              @Nullable EndpointInfo endpoint, OpenApiRoute route) {
        final String httpMethod = method.httpMethod().name().toLowerCase(Locale.ENGLISH);
        // Do not use ObjectNode.with(): a path starts with '/' and Jackson treats that as a pointer.
        ObjectNode pathItem = (ObjectNode) paths.get(route.path);
        if (pathItem == null) {
            pathItem = paths.putObject(route.path);
        }
        if (pathItem.has(httpMethod)) {
            return;
        }

        final ObjectNode operation = pathItem.putObject(httpMethod);
        operation.put("operationId", operationId(service.name(), method.name(), httpMethod, route.path));
        operation.put("summary", method.name());
        operation.putArray("tags").add(service.name());
        final String description = description(service, method, endpoint, route);
        if (!description.isEmpty()) {
            operation.put("description", description);
        }

        final ArrayNode parameters = parameters(service.name(), method, route.path);
        if (parameters.size() > 0) {
            operation.set("parameters", parameters);
        }

        final ObjectNode requestSchema = requestSchema(service.name(), method);
        if (requestSchema != null && allowsRequestBody(httpMethod)) {
            final ObjectNode body = operation.putObject("requestBody");
            body.put("required", requestRequired(method));
            if (method.useParameterAsRoot()) {
                final String paramDoc =
                        paramDescription(service.name(), method, method.parameters().get(0));
                if (!paramDoc.isEmpty()) {
                    body.put("description", paramDoc);
                }
            }
            final ObjectNode json = body.putObject("content").putObject("application/json");
            json.set("schema", requestSchema);
            addExample(json, method.exampleRequests());
        }

        final ObjectNode responses = operation.putObject("responses");
        final ObjectNode ok = responses.putObject("200");
        final String returnDoc = docString(service.name() + '/' + method.name() + ":return");
        if (isVoid(method.returnTypeSignature())) {
            ok.put("description", returnDoc.isEmpty() ? "Empty response" : returnDoc);
        } else {
            ok.put("description", returnDoc.isEmpty() ? "Success" : returnDoc);
            ok.putObject("content").putObject("application/json")
              .set("schema", schemaForType(method.returnTypeSignature()));
        }
    }

    private ArrayNode parameters(String serviceName, MethodInfo method, String path) {
        final ArrayNode parameters = mapper.createArrayNode();
        final Set<String> seen = new LinkedHashSet<>();
        for (ParamInfo param : method.parameters()) {
            final String in = parameterIn(param.location());
            if (in == null || !seen.add(param.name())) {
                continue;
            }
            parameters.add(parameter(serviceName, method, param, in));
        }
        final Matcher matcher = BRACE_PARAM.matcher(path);
        while (matcher.find()) {
            final String name = matcher.group(1);
            if (!seen.add(name)) {
                continue;
            }
            final ObjectNode parameter = mapper.createObjectNode();
            parameter.put("name", name);
            parameter.put("in", "path");
            parameter.put("required", true);
            parameter.putObject("schema").put("type", "string");
            if ("remainingPath".equals(name)) {
                parameter.put("description", "Remainder of a prefix route.");
            }
            parameters.add(parameter);
        }
        return parameters;
    }

    private ObjectNode parameter(String serviceName, MethodInfo method, ParamInfo param, String in) {
        final ObjectNode parameter = mapper.createObjectNode();
        parameter.put("name", param.name());
        parameter.put("in", in);
        parameter.put("required", "path".equals(in) || param.requirement() == FieldRequirement.REQUIRED);
        parameter.set("schema", schemaForType(param.typeSignature()));
        final String description = paramDescription(serviceName, method, param);
        if (!description.isEmpty()) {
            parameter.put("description", description);
        }
        return parameter;
    }

    @Nullable
    private ObjectNode requestSchema(String serviceName, MethodInfo method) {
        if (method.parameters().isEmpty()) {
            return null;
        }
        if (method.useParameterAsRoot()) {
            // Keep a direct $ref. The parameter description is attached to the request body.
            return schemaForType(method.parameters().get(0).typeSignature());
        }

        final List<ParamInfo> bodyParams = new ArrayList<>();
        boolean hasBody = false;
        boolean hasNonBodyLocation = false;
        for (ParamInfo param : method.parameters()) {
            final FieldLocation location = param.location();
            if (location == FieldLocation.PATH || location == FieldLocation.QUERY ||
                location == FieldLocation.HEADER) {
                hasNonBodyLocation = true;
                continue;
            }
            if (location == FieldLocation.BODY) {
                hasBody = true;
            }
            bodyParams.add(param);
        }
        if (bodyParams.isEmpty()) {
            return null;
        }
        // An annotated request object sits beside path or query parameters and is the whole body.
        // Body fields, including a single one, stay properties so the shape matches DocService.
        if (!hasBody && hasNonBodyLocation && bodyParams.size() == 1 &&
            bodyParams.get(0).typeSignature().type() == TypeSignatureType.STRUCT) {
            final ParamInfo param = bodyParams.get(0);
            return withDescription(schemaForType(param.typeSignature()),
                                   paramDescription(serviceName, method, param));
        }

        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        final ObjectNode properties = schema.putObject("properties");
        final ArrayNode required = mapper.createArrayNode();
        for (ParamInfo param : bodyParams) {
            properties.set(param.name(), withDescription(schemaForType(param.typeSignature()),
                                                         paramDescription(serviceName, method, param)));
            if (param.requirement() == FieldRequirement.REQUIRED) {
                required.add(param.name());
            }
        }
        if (required.size() > 0) {
            schema.set("required", required);
        }
        return schema;
    }

    private ObjectNode schemas() {
        final ObjectNode schemas = mapper.createObjectNode();
        for (StructInfo struct : specification.structs()) {
            schemas.set(struct.name(), structSchema(struct));
        }
        for (EnumInfo enumInfo : specification.enums()) {
            if (!schemas.has(enumInfo.name())) {
                schemas.set(enumInfo.name(), enumSchema(enumInfo));
            }
        }
        for (ExceptionInfo exception : specification.exceptions()) {
            if (!schemas.has(exception.name())) {
                schemas.set(exception.name(), exceptionSchema(exception));
            }
        }
        return schemas;
    }

    private ObjectNode structSchema(StructInfo struct) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        putDescription(schema, struct.descriptionInfo().docString());
        if (!struct.oneOf().isEmpty()) {
            final ArrayNode oneOf = schema.putArray("oneOf");
            for (TypeSignature sub : struct.oneOf()) {
                oneOf.addObject().put("$ref", ref(sub.name()));
            }
            final DiscriminatorInfo discriminator = struct.discriminator();
            if (discriminator != null) {
                final ObjectNode discriminatorNode = schema.putObject("discriminator");
                discriminatorNode.put("propertyName", discriminator.propertyName());
                if (!discriminator.mapping().isEmpty()) {
                    final ObjectNode mapping = discriminatorNode.putObject("mapping");
                    for (Entry<String, String> entry : discriminator.mapping().entrySet()) {
                        mapping.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            return schema;
        }

        final ObjectNode properties = mapper.createObjectNode();
        final DiscriminatorInfo discriminator = polymorphismToBase.get(struct.name());
        if (discriminator != null) {
            properties.putObject(discriminator.propertyName()).put("type", "string");
        }
        final ArrayNode required = mapper.createArrayNode();
        for (FieldInfo field : struct.fields()) {
            properties.set(field.name(), withDescription(schemaForType(field.typeSignature()),
                                                         field.descriptionInfo().docString()));
            if (field.requirement() == FieldRequirement.REQUIRED) {
                required.add(field.name());
            }
        }
        if (discriminator != null) {
            required.add(discriminator.propertyName());
        }
        if (properties.size() > 0) {
            schema.set("properties", properties);
        }
        if (required.size() > 0) {
            schema.set("required", required);
        }
        return schema;
    }

    private ObjectNode exceptionSchema(ExceptionInfo exception) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        putDescription(schema, exception.descriptionInfo().docString());
        final ObjectNode properties = schema.putObject("properties");
        final ArrayNode required = mapper.createArrayNode();
        for (FieldInfo field : exception.fields()) {
            properties.set(field.name(), withDescription(schemaForType(field.typeSignature()),
                                                         field.descriptionInfo().docString()));
            if (field.requirement() == FieldRequirement.REQUIRED) {
                required.add(field.name());
            }
        }
        if (required.size() > 0) {
            schema.set("required", required);
        }
        return schema;
    }

    private ObjectNode enumSchema(EnumInfo enumInfo) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        putDescription(schema, enumInfo.descriptionInfo().docString());
        final ArrayNode values = schema.putArray("enum");
        for (EnumValueInfo value : enumInfo.values()) {
            values.add(value.name());
        }
        return schema;
    }

    private ObjectNode schemaForType(TypeSignature typeSignature) {
        switch (typeSignature.type()) {
            case STRUCT:
            case ENUM:
                return refNode(typeSignature.name());
            case OPTIONAL:
            case CONTAINER:
                return schemaForType(((ContainerTypeSignature) typeSignature).typeParameters().get(0));
            case ITERABLE:
                final ObjectNode array = mapper.createObjectNode();
                array.put("type", "array");
                final TypeSignature item =
                        ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
                array.set("items", schemaForType(item));
                return array;
            case MAP:
                final ObjectNode map = mapper.createObjectNode();
                map.put("type", "object");
                map.set("additionalProperties",
                        schemaForType(((MapTypeSignature) typeSignature).valueTypeSignature()));
                return map;
            case UNRESOLVED:
                final ObjectNode unresolved = mapper.createObjectNode();
                unresolved.put("type", "object");
                unresolved.put("description", "Unresolved type: " + typeSignature.name());
                return unresolved;
            default:
                return baseSchema(typeSignature.name());
        }
    }

    private ObjectNode baseSchema(String typeName) {
        final ObjectNode node = mapper.createObjectNode();
        switch (typeName.toLowerCase(Locale.ROOT)) {
            case "boolean":
            case "bool":
                node.put("type", "boolean");
                break;
            case "i8":
            case "i16":
            case "i32":
            case "int":
            case "integer":
            case "int32":
            case "uint32":
            case "sint32":
            case "fixed32":
            case "sfixed32":
                node.put("type", "integer");
                node.put("format", "int32");
                break;
            case "i64":
            case "long":
            case "int64":
            case "uint64":
            case "sint64":
            case "fixed64":
            case "sfixed64":
                node.put("type", "integer");
                node.put("format", "int64");
                break;
            case "float":
                node.put("type", "number");
                node.put("format", "float");
                break;
            case "double":
                node.put("type", "number");
                node.put("format", "double");
                break;
            case "short":
                node.put("type", "number");
                break;
            case "binary":
            case "byte":
            case "bytes":
                node.put("type", "string");
                node.put("format", "byte");
                break;
            case "string":
                node.put("type", "string");
                break;
            default:
                node.put("type", "object");
                node.put("description", "Unrecognized type: " + typeName);
                break;
        }
        return node;
    }

    private ObjectNode withDescription(ObjectNode schema, String description) {
        if (description.isEmpty()) {
            return schema;
        }
        if (schema.has("$ref")) {
            final ObjectNode wrapped = mapper.createObjectNode();
            wrapped.putArray("allOf").add(schema);
            wrapped.put("description", description);
            return wrapped;
        }
        schema.put("description", description);
        return schema;
    }

    private void addExample(ObjectNode media, List<String> examples) {
        if (examples.isEmpty()) {
            return;
        }
        final String example = examples.get(0);
        try {
            media.set("example", mapper.readTree(example));
        } catch (JsonProcessingException ignored) {
            media.put("example", example);
        }
    }

    private String description(ServiceInfo service, MethodInfo method,
                               @Nullable EndpointInfo endpoint, OpenApiRoute route) {
        final StringBuilder builder = new StringBuilder();
        appendParagraph(builder, docString(service.name() + '/' + method.name()));
        if (!method.exceptionTypeSignatures().isEmpty()) {
            final StringBuilder thrown = new StringBuilder("May throw:");
            for (TypeSignature exception : method.exceptionTypeSignatures()) {
                thrown.append(' ').append(exception.name()).append(',');
            }
            thrown.setLength(thrown.length() - 1);
            appendParagraph(builder, thrown.toString());
        }
        if (endpoint != null && !endpoint.availableMimeTypes().isEmpty()) {
            final StringBuilder types = new StringBuilder("Content types:");
            for (MediaType mediaType : endpoint.availableMimeTypes()) {
                types.append(' ').append(mediaType).append(',');
            }
            types.setLength(types.length() - 1);
            appendParagraph(builder, types.toString());
        }
        if (endpoint != null && !"*".equals(endpoint.hostnamePattern()) &&
            !endpoint.hostnamePattern().isEmpty()) {
            appendParagraph(builder, "Host: " + endpoint.hostnamePattern());
        }
        appendParagraph(builder, route.note);
        return builder.toString();
    }

    private String paramDescription(String serviceName, MethodInfo method, ParamInfo param) {
        return docString(serviceName + '/' + method.name() + ":param/" + param.name());
    }

    private String docString(String key) {
        final DescriptionInfo description = docStrings.get(key);
        if (description == null) {
            return "";
        }
        return description.docString();
    }

    private static void appendParagraph(StringBuilder builder, String paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(paragraph);
    }

    private static void putDescription(ObjectNode node, String description) {
        if (!description.isEmpty()) {
            node.put("description", description);
        }
    }

    private static boolean requestRequired(MethodInfo method) {
        if (method.useParameterAsRoot()) {
            return method.parameters().get(0).requirement() != FieldRequirement.OPTIONAL;
        }
        for (ParamInfo param : method.parameters()) {
            if (param.location() != FieldLocation.BODY && param.location() != FieldLocation.UNSPECIFIED) {
                continue;
            }
            if (param.requirement() == FieldRequirement.REQUIRED) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowsRequestBody(String httpMethod) {
        return !"head".equals(httpMethod);
    }

    private static boolean isVoid(TypeSignature typeSignature) {
        return typeSignature.type() == TypeSignatureType.BASE &&
               "void".equals(typeSignature.name().toLowerCase(Locale.ROOT));
    }

    @Nullable
    private static String parameterIn(FieldLocation location) {
        switch (location) {
            case PATH:
                return "path";
            case QUERY:
                return "query";
            case HEADER:
                return "header";
            case BODY:
            case UNSPECIFIED:
            default:
                return null;
        }
    }

    private static OpenApiRoute openApiRoute(EndpointInfo endpoint) {
        String path = endpoint.pathMapping();
        boolean prefix = false;
        String note = "";
        if (path.startsWith("exact:")) {
            path = path.substring("exact:".length());
        } else if (path.startsWith("prefix:")) {
            path = path.substring("prefix:".length());
            prefix = true;
        } else if (path.startsWith("regex:")) {
            final String regex = path.substring("regex:".length());
            final String regexPrefix = endpoint.regexPathPrefix();
            if (regexPrefix == null) {
                path = "/";
            } else if (regexPrefix.startsWith("prefix:")) {
                path = regexPrefix.substring("prefix:".length());
                prefix = true;
            } else {
                path = regexPrefix;
            }
            note = "Regex route: " + regex;
        }
        path = COLON_PARAM.matcher(path).replaceAll("{$1}");
        if (path.isEmpty()) {
            path = "/";
        }
        if (path.charAt(0) != '/') {
            path = '/' + path;
        }
        if (prefix) {
            if (path.charAt(path.length() - 1) != '/') {
                path = path + '/';
            }
            path = path + "{remainingPath}";
            if (note.isEmpty()) {
                note = "Prefix route.";
            }
        }
        return new OpenApiRoute(path, note);
    }

    private static String operationId(String serviceName, String methodName, String httpMethod, String path) {
        final String raw = serviceName + '_' + methodName + '_' + httpMethod + '_' + path;
        final StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char ch = raw.charAt(i);
            final boolean keep = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
                                 (ch >= '0' && ch <= '9') || ch == '_';
            builder.append(keep ? ch : '_');
        }
        if (builder.length() == 0 || (builder.charAt(0) >= '0' && builder.charAt(0) <= '9')) {
            builder.insert(0, "op_");
        }
        return builder.toString();
    }

    private static ObjectNode refNode(String name) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("$ref", ref(name));
        return node;
    }

    private static String ref(String name) {
        return "#/components/schemas/" + name.replace("~", "~0").replace("/", "~1");
    }

    private static Map<String, DiscriminatorInfo> polymorphismToBase(ServiceSpecification specification) {
        final Map<String, String> nameToAlias = new HashMap<>();
        for (StructInfo struct : specification.structs()) {
            if (struct.alias() != null) {
                nameToAlias.put(struct.name(), struct.alias());
            }
        }
        final Map<String, DiscriminatorInfo> result = new HashMap<>();
        for (StructInfo struct : specification.structs()) {
            final DiscriminatorInfo discriminator = struct.discriminator();
            if (discriminator == null) {
                continue;
            }
            for (TypeSignature sub : struct.oneOf()) {
                result.putIfAbsent(sub.name(), discriminator);
                final String alias = nameToAlias.get(sub.name());
                if (alias != null) {
                    result.putIfAbsent(alias, discriminator);
                }
            }
        }
        return result;
    }

    private static final class OpenApiRoute {
        private final String path;
        private final String note;

        private OpenApiRoute(String path, String note) {
            this.path = path;
            this.note = note;
        }
    }
}
