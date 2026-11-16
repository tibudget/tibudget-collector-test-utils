package com.tibudget.test.simulator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.tibudget.api.CollectorPlugin;
import com.tibudget.api.Input;
import com.tibudget.api.exceptions.ParameterError;
import com.tibudget.dto.MessageDto;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/**
 * Build the collector settings dialog from its inherited {@link Input} declarations.
 * Use the documented getter/setter contract instead of writing private fields directly.
 */
final class SimulatorInputs {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final List<Property> properties = new ArrayList<>();
    private final Supplier<CollectorPlugin> factory;
    private final ResourceBundle messages;

    SimulatorInputs(Supplier<CollectorPlugin> factory, ResourceBundle messages) throws Exception {
        this.factory = factory;
        this.messages = messages;
        CollectorPlugin collector = factory.get();
        Map<String, PropertyDescriptor> descriptors = new HashMap<>();
        for (PropertyDescriptor descriptor : Introspector.getBeanInfo(collector.getClass()).getPropertyDescriptors()) descriptors.put(descriptor.getName(), descriptor);
        Set<String> names = new HashSet<>();
        for (Class<?> type = collector.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Input input = field.getAnnotation(Input.class);
                if (input == null) continue;
                PropertyDescriptor descriptor = descriptors.get(field.getName());
                if (!names.add(field.getName()) || Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()) || descriptor == null
                        || descriptor.getReadMethod() == null || descriptor.getWriteMethod() == null || descriptor.getPropertyType() != field.getType()) {
                    throw new IllegalStateException("Input needs a unique name and matching public getter/setter: " + field.getName());
                }
                Class<?> valueType = field.getType();
                if (!supported(valueType) || input.password() && valueType != String.class) throw new IllegalStateException("Unsupported input type: " + field.getName());
                properties.add(new Property(field.getName(), valueType, input, descriptor, descriptor.getReadMethod().invoke(collector)));
            }
        }
        properties.sort(Comparator.comparingInt((Property property) -> property.input.order()).thenComparing(Property::name));
    }

    Optional<Map<String, Object>> show(Window owner, Map<String, Object> previous) {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("Collector settings");
        dialog.initOwner(owner);
        dialog.setHeaderText("Configure your test connection");
        dialog.setResizable(true);
        dialog.getDialogPane().getStylesheets().add(Objects.requireNonNull(getClass().getResource("simulator.css")).toExternalForm());
        ButtonType save = new ButtonType("Save settings", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        VBox form = new VBox(14);
        form.setFillWidth(true);
        Label explanation = new Label("Inputs are saved encrypted in your user configuration directory. The encryption key is local to your OS account.");
        explanation.setWrapText(true);
        explanation.getStyleClass().add("muted");
        form.getChildren().add(explanation);
        Map<Property, Node> editors = new LinkedHashMap<>();
        Map<Property, VBox> rows = new LinkedHashMap<>();
        for (Property property : properties) {
            Object value = previous.containsKey(property.name) ? previous.get(property.name) : property.defaultValue;
            Node editor;
            if (property.type == boolean.class || property.type == Boolean.class) {
                CheckBox check = new CheckBox("Enabled");
                check.setSelected(Boolean.TRUE.equals(value));
                editor = check;
            } else if (property.type.isEnum()) {
                ComboBox<Object> select = new ComboBox<>();
                select.getItems().addAll(property.type.getEnumConstants());
                select.setValue(value);
                select.setMaxWidth(Double.MAX_VALUE);
                editor = select;
            } else {
                TextField text = property.input.password() ? new PasswordField() : new TextField();
                text.setText(value == null ? "" : value.toString());
                text.setPromptText(property.type == String.class ? "Enter " + property.name : property.type.getSimpleName());
                editor = text;
            }
            editor.setId("input-" + property.name);
            Label label = new Label(message("form.label." + property.name, property.name) + (property.input.required() ? " *" : ""));
            label.getStyleClass().add("field-label");
            label.setLabelFor(editor);
            VBox row = new VBox(6, label, editor);
            String help = message("form.tooltip." + property.name, "");
            if (!help.isBlank()) {
                Label hint = new Label(help);
                hint.setWrapText(true);
                hint.getStyleClass().add("muted");
                row.getChildren().add(hint);
            }
            rows.put(property, row);
            editors.put(property, editor);
            form.getChildren().add(row);
        }
        Runnable visibility = () -> rows.forEach((property, row) -> {
            boolean visible = true;
            if (!property.input.fieldset().isEmpty()) {
                for (Map.Entry<Property, Node> entry : editors.entrySet()) {
                    if (!(entry.getValue() instanceof CheckBox check)) continue;
                    Input condition = entry.getKey().input;
                    if (Arrays.asList(condition.hideFieldset()).contains(property.input.fieldset()) && check.isSelected()) visible = false;
                    if (Arrays.asList(condition.showFieldset()).contains(property.input.fieldset()) && !check.isSelected()) visible = false;
                }
            }
            row.setVisible(visible);
            row.setManaged(visible);
        });
        editors.values().forEach(editor -> {
            if (editor instanceof CheckBox check) check.selectedProperty().addListener((observable, oldValue, value) -> visibility.run());
        });
        visibility.run();
        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("error-text");
        form.getChildren().add(error);
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(470);
        scroll.setPrefViewportHeight(Math.min(520, 130 + properties.size() * 100));
        dialog.getDialogPane().setContent(scroll);
        Map<String, Object> accepted = new LinkedHashMap<>();
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Property property : properties) {
                    if (!rows.get(property).isVisible()) continue;
                    Node editor = editors.get(property);
                    Object value;
                    if (editor instanceof CheckBox check) value = check.isSelected();
                    else if (editor instanceof ComboBox<?> select) value = select.getValue();
                    else value = convert(((TextField) editor).getText(), property.type, property.input);
                    if (property.input.required() && (value == null || value instanceof String text && text.isBlank())) throw new IllegalArgumentException("Required input: " + property.name);
                    values.put(property.name, value);
                }
                CollectorPlugin candidate = factory.get();
                apply(candidate, values);
                List<MessageDto> errors = candidate.validate().stream().filter(msg -> msg.getType() == MessageDto.MessageType.ERROR).toList();
                if (!errors.isEmpty()) throw new IllegalArgumentException(errors.stream().map(msg -> message(msg.getMessageKey(), msg.getMessageKey())).reduce((a, b) -> a + "\n" + b).orElse("Invalid inputs"));
                accepted.putAll(values);
            } catch (Exception failure) {
                error.setText(failure.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? accepted : null);
        return dialog.showAndWait();
    }

    JsonArray schema() {
        JsonArray schema = new JsonArray();
        for (Property property : properties) {
            JsonObject input = new JsonObject();
            input.addProperty("name", property.name);
            input.addProperty("type", property.type.getSimpleName());
            input.addProperty("required", property.input.required());
            input.addProperty("password", property.input.password());
            input.addProperty("maxLength", property.input.maxlen());
            if (property.type.isEnum()) input.add("choices", JSON.toJsonTree(property.type.getEnumConstants()));
            schema.add(input);
        }
        return schema;
    }

    void checkNames(JsonObject saved) {
        Set<String> names = new HashSet<>();
        properties.forEach(property -> names.add(property.name));
        if (!names.containsAll(saved.keySet())) throw new IllegalArgumentException("Unknown collector input name");
    }

    JsonObject savedValues(Map<String, Object> values) {
        return JSON.toJsonTree(values).getAsJsonObject();
    }

    Map<String, Object> restore(JsonObject saved, DiagnosticRedactor redactor) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Property property : properties) {
            if (saved.has(property.name)) values.put(property.name, JSON.fromJson(saved.get(property.name), property.type));
        }
        protect(values, redactor);
        CollectorPlugin candidate = factory.get();
        apply(candidate, values);
        if (candidate.validate().stream().anyMatch(message -> message.getType() == MessageDto.MessageType.ERROR)) {
            throw new IllegalArgumentException("Saved inputs need validation in Settings");
        }
        return values;
    }

    void apply(CollectorPlugin collector, Map<String, Object> values) throws Exception {
        for (Property property : properties) {
            if (!values.containsKey(property.name)) continue;
            try {
                property.descriptor.getWriteMethod().invoke(collector, values.get(property.name));
            } catch (InvocationTargetException failure) {
                String key = failure.getCause() instanceof ParameterError error ? error.getMessage() : "Input setter failed";
                throw new IllegalArgumentException(property.name + ": " + message(key, key));
            }
        }
    }

    String identity(Map<String, Object> values) throws Exception {
        StringBuilder identity = new StringBuilder();
        for (Property property : properties) {
            if (!property.input.password()) identity.append(property.name).append('=').append(values.get(property.name)).append('\n');
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
    }

    void protect(Map<String, Object> values, DiagnosticRedactor redactor) {
        for (Property property : properties) {
            Object value = values.get(property.name);
            if (value instanceof String text && (property.input.password() || redactor.sensitive(property.name))) redactor.remember(text);
        }
    }

    private String message(String key, String fallback) {
        return messages != null && messages.containsKey(key) ? messages.getString(key) : fallback;
    }

    private static boolean supported(Class<?> type) {
        return type == String.class || type == Boolean.class || type == boolean.class || type == Character.class || type == char.class || type.isEnum()
                || type == Byte.class || type == byte.class || type == Short.class || type == short.class || type == Integer.class || type == int.class
                || type == Long.class || type == long.class || type == Float.class || type == float.class || type == Double.class || type == double.class;
    }

    static Object convert(String text, Class<?> type, Input input) {
        if (text.isEmpty() && !input.required() && !type.isPrimitive()) return null;
        if (input.maxlen() >= 0 && text.length() > input.maxlen()) throw new IllegalArgumentException("Input exceeds its maximum length");
        if (type == String.class) return text;
        try {
            if (type == char.class || type == Character.class) {
                if (text.length() != 1) throw new IllegalArgumentException();
                return text.charAt(0);
            }
            if (type == byte.class || type == Byte.class) return Byte.valueOf(text);
            if (type == short.class || type == Short.class) return Short.valueOf(text);
            if (type == int.class || type == Integer.class) return Integer.valueOf(text);
            if (type == long.class || type == Long.class) return Long.valueOf(text);
            if (type == float.class || type == Float.class) {
                float value = Float.parseFloat(text);
                if (!Float.isFinite(value)) throw new IllegalArgumentException();
                return value;
            }
            if (type == double.class || type == Double.class) {
                double value = Double.parseDouble(text);
                if (!Double.isFinite(value)) throw new IllegalArgumentException();
                return value;
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Expected " + type.getSimpleName() + " input");
        }
        throw new IllegalArgumentException("Unsupported input type");
    }

    private record Property(String name, Class<?> type, Input input, PropertyDescriptor descriptor, Object defaultValue) {
    }
}
