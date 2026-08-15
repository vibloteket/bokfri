package org.fribok.bookkeeping.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Locale;

/** Generates versioned JSON Schema documents from the CLI's actual input classes. */
public final class CliJsonSchemaService {
    private static final String SCHEMA_BASE = "https://bokfri.viblo.se/schemas/cli/";
    private final SchemaGenerator generator;

    /** Creates the shared Draft 2020-12 schema generator. */
    public CliJsonSchemaService() {
        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule())
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.NOT_NULLABLE_METHOD_IS_REQUIRED))
                .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        builder.forTypesInGeneral().withCustomDefinitionProvider((type, context) -> {
            if (!java.math.BigDecimal.class.equals(type.getErasedType())) {
                return null;
            }
            ObjectNode decimal = context.getGeneratorConfig().createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode oneOf = decimal.putArray("oneOf");
            oneOf.addObject().put("type", "string")
                    .put("pattern", "^-?[0-9]+(?:\\.[0-9]+)?$");
            oneOf.addObject().put("type", "number");
            return new CustomDefinition(decimal, CustomDefinition.INLINE_DEFINITION,
                    CustomDefinition.EXCLUDING_ATTRIBUTES);
        });
        builder.forFields().withRequiredCheck(field ->
                field.getAnnotationConsideringFieldAndGetter(NotNull.class) != null
                        || field.getAnnotationConsideringFieldAndGetter(NotBlank.class) != null);
        SchemaGeneratorConfig config = builder.build();
        generator = new SchemaGenerator(config);
    }

    /**
     * Generates a schema for one CLI input contract.
     *
     * @param name stable CLI object name
     * @param inputType Jackson input class used by validate/create
     * @return generated JSON Schema
     */
    public JsonNode generate(String name, Class<?> inputType) {
        ObjectNode schema = generator.generateSchema(inputType);
        schema.put("$id", SCHEMA_BASE + name.toLowerCase(Locale.ROOT) + "-v1.schema.json");
        schema.put("title", "Bokfri " + name + " input");
        ObjectNode properties = schema.withObject("properties");
        addRequiredProperties(schema, inputType);
        if (hasRequiredAnnotation(inputType, "rows")) {
            addRequired(schema, "rows");
        }
        if (hasRequiredAnnotation(inputType, "balances")) {
            addRequired(schema, "balances");
        }
        JsonNode schemaVersion = properties.get("schemaVersion");
        if (schemaVersion instanceof ObjectNode version) {
            version.remove("type");
            version.put("const", 1);
            version.put("default", 1);
        }
        return schema;
    }

    private static boolean hasRequiredAnnotation(Class<?> inputType, String fieldName) {
        try {
            java.lang.reflect.Field field = inputType.getDeclaredField(fieldName);
            return field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(NotBlank.class);
        } catch (NoSuchFieldException exception) {
            return false;
        }
    }

    private static void addRequired(ObjectNode schema, String property) {
        com.fasterxml.jackson.databind.node.ArrayNode required = schema.withArray("required");
        for (JsonNode current : required) {
            if (property.equals(current.asText())) {
                return;
            }
        }
        required.add(property);
    }

    private static void addRequiredProperties(ObjectNode schema, Class<?> inputType) {
        com.fasterxml.jackson.databind.node.ArrayNode required = schema.withArray("required");
        java.util.Set<String> names = new java.util.TreeSet<>();
        required.forEach(node -> names.add(node.asText()));
        for (java.lang.reflect.Field field : inputType.getDeclaredFields()) {
            if (field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(NotBlank.class)) {
                names.add(field.getName());
            }
        }
        required.removeAll();
        names.forEach(required::add);
        if (required.isEmpty()) {
            schema.remove("required");
        }
    }
}
