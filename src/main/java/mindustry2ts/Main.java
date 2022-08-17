package mindustry2ts;

import arc.struct.IntSeq;
import arc.struct.ObjectIntMap;
import arc.util.Reflect;
import arc.util.Strings;
import mindustry.net.Net;

import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    static Path resultDir = Path.of("result/");

    static ObjectIntMap<Class<?>> packetToId = Reflect.get(Net.class, "packetToId");

    static Set<String> defaultImports = Set.of(
            "import {BufferWriter, BufferReader} from '../io';",
            "import {Packet} from './packet';");

    static Map<Class<?>, String> mappedImports = Map.of(
            String.class, "import {Nullable} from '../util/types/nullable';"
    );

    static Set<String> types = new TreeSet<>();

    public static void main(String[] args) throws Throwable {
        if (Files.notExists(resultDir)) {
            Files.createDirectory(resultDir);
        }

        String filename = "call.ts";
        try (Writer call = Files.newBufferedWriter(resultDir.resolve(filename))) {
            call.append("export default class Call {\n\n");
            // Метод со статической регистрацией
            StringBuilder registerPackets = new StringBuilder();
            registerPackets.append("\tpublic static registerPackets(): void {\n");

            for (var e : packetToId) {
                String simpleName = e.key.getSimpleName();
                registerPackets.append("\t\tNet.registerPacket(").append(simpleName).append(", ");
                registerPackets.append(simpleName).append(".ID);\n");

                generatePacket(e.key, e.value);
            }

            // Есть проблемы с определением пакетов
            // for (Method method : Call.class.getDeclaredMethods()) {
            //     if (method.getName().equals("registerPackets")) continue;
            //
            //     boolean forwarded = method.getName().endsWith("__forward");
            //     boolean toAll = true;
            //     StringJoiner paramsJoiner = new StringJoiner(", ");
            //
            //     var params = method.getParameters();
            //
            //     // Возможно это тот, кому нужно отослать пакет, так что вот
            //     boolean firstIsNetCon = params.length >= 1 && params[0].getType() == NetConnection.class;
            //     int offset = firstIsNetCon ? 1 : 0;
            //     String typeSeq = Arrays.stream(params).skip(offset)
            //             .map(p -> p.getParameterizedType().toString())
            //             .collect(Collectors.joining(","));
            //
            //     Class<?> type = fieldsToTypes.get(typeSeq);
            //     if (type == null)
            //         System.out.println(typeSeq);
            //     var fields = Arrays.stream(type.getDeclaredFields())
            //             .filter(f -> !f.getName().equals("DATA"))
            //             .collect(Collectors.toList());
            //
            //     for (int i = 0; i < params.length; i++) {
            //         Parameter p = params[i];
            //         String name;
            //         if (i == 0 && firstIsNetCon) {
            //             name = forwarded ? "exceptConnection" : "playerConnection";
            //         } else {
            //             name = fields.get(i - offset).getName();
            //         }
            //
            //         paramsJoiner.add(name + ": " + mapType(p.getParameterizedType()));
            //     }
            //
            //     // boolean overload = forwarded;
            //
            //     call.append('\t');
            //     if (!forwarded) {
            //         call.append("public ");
            //     }
            //
            //     call.append("static ").append(method.getName()).append("(");
            //     call.append(paramsJoiner.toString()).append(") {}\n\n");
            // }

            registerPackets.append("\t}");
            call.append(registerPackets);
            call.append("\n}\n");
        }

        System.out.println("unhandled types = " + types);
    }

    static void generatePacket(Class<?> klass, int id) throws Throwable {
        String name = klass.getSimpleName();
        var filename = Strings.camelToKebab(name) + ".ts";
        try (var writer = Files.newBufferedWriter(resultDir.resolve(filename))) {
            List<Field> fields = Arrays.stream(klass.getDeclaredFields())
                    .filter(f -> !f.getName().equals("DATA"))
                    .collect(Collectors.toList());
            boolean empty = fields.isEmpty(); // ради красивого форматирования
            collectImports(writer, fields);

            writer.append("export default class ").append(name).append(" implements Packet {\n");
            writer.append("\tpublic static ID = ").append(Integer.toString(id)).append(";\n\n");

            StringJoiner params = new StringJoiner(", ");
            StringBuilder assignments = new StringBuilder();

            StringBuilder serializer = new StringBuilder();
            serializer.append("\tserialize(buf: BufferWriter) {");

            StringBuilder deserializer = new StringBuilder();
            deserializer.append("\tdeserialize(buf: BufferReader) {");

            if (!empty) {
                serializer.append('\n');
                deserializer.append('\n');
            } else {
                serializer.append("}\n\n");
                deserializer.append('}');
            }

            for (int i = 0, n = fields.size(); i < n; i++) {
                Field field = fields.get(i);
                String mapType = mapType(field.getGenericType());

                params.add(field.getName() + ": " + mapType);

                // сериализация
                serializer.append("\t\tbuf.").append(serializerMethod(field.getType(), klass)).append("(this.");
                serializer.append(field.getName()).append(");\n");

                // десериализация
                deserializer.append("\t\tthis.").append(field.getName()).append(" = buf.");
                deserializer.append(deserializerMethod(field.getType(), klass)).append("();\n");

                assignments.append("\t\tthis.").append(field.getName()).append(" = args[").append(i).append("];\n");
                writer.append("\tpublic ").append(field.getName()).append(": ").append(mapType).append(";\n");
            }

            if (!empty) {
                writer.append('\n'); // немного красоты

                // конструктор без параметров
                writer.append("\tconstructor();\n\n");

                // и конструктор со всеми параметрами
                writer.append("\tconstructor(").append(params.toString()).append(");\n\n");

                // собственно реализация конструктора
                writer.append("\tconstructor(...args: any[]) {\n");
                writer.append("\t\tif (args.length == 0) return;\n");
                writer.append(assignments);
                writer.append("\t}\n\n");
            }

            // id(): number метод
            writer.append("\tgetId() {\n");
            writer.append("\t\treturn ").append(name).append(".ID;\n");
            writer.append("\t}\n\n");

            if (!empty) {
                serializer.append("\t}\n\n");
                deserializer.append("\t}");
            }

            writer.append(serializer);

            writer.append(deserializer);
            writer.append("\n}\n");
        }
    }

    static void collectImports(Writer writer, List<Field> fields) throws Throwable {
        Set<String> imports = new TreeSet<>(defaultImports);
        for (Field field : fields) {
            String cl = mappedImports.get(field.getType());
            if (cl != null) {
                imports.add(cl);
            }
        }

        for (String anImport : imports) {
            writer.append(anImport).append('\n');
        }
        writer.append('\n');
    }

    static String serializerMethod(Class<?> type, Class<?> klass) {
        if (type.isPrimitive()) {
            // умный и крутой Саммет! Спасибо за хорошо названные методы
            return "write" + Strings.capitalize(type.getSimpleName());
        }
        return "$";
    }

    static String deserializerMethod(Class<?> type, Class<?> klass) {
        if (type.isPrimitive()) {
            return "read" + Strings.capitalize(type.getSimpleName());
        }
        return "$";
    }

    static String mapType(Type type) {
        if (type == byte.class || type == short.class || type == int.class || type == float.class) {
            return "number";
        } else if (type == long.class) {
            return "bigint";
        } else if (type == boolean.class) {
            return "boolean";
        } else if (type == String.class) {
            return "Nullable<string>";
        } else if (type == Object.class) {
            return "any";
        } else if (type == IntSeq.class) {
            return "number[]";
        } else if (type instanceof Class<?> c && c.isArray()) {
            return mapType(c.componentType()) + "[]";
        } else if (type instanceof ParameterizedType p) {
            Class<?> param = (Class<?>) p.getActualTypeArguments()[0];
            return mapType(param) + "[]";
        }

        types.add(type.getTypeName());
        return null;
    }
}
