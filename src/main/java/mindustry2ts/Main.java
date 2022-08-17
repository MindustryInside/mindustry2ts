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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public class Main {

    static final int lineWrap = 100;

    static final Path resultDir = Path.of("result/");

    static final ObjectIntMap<Class<?>> packetToId = Reflect.get(Net.class, "packetToId");

    static final Set<String> imports = Set.of(
            "import {BufferWriter, BufferReader} from '../io';",
            "import {Packet} from './packet';",
            "import {Nullable} from '../util/types/nullable';");

    static final Set<String> types = new HashSet<>();

    public static void main(String[] args) throws Throwable {
        if (Files.notExists(resultDir)) {
            Files.createDirectory(resultDir);
        }

        Writer packets = Files.newBufferedWriter(resultDir.resolve("packets.ts"));
        Writer call = Files.newBufferedWriter(resultDir.resolve("call.ts"));

        call.append("import {Packets as p} from './packets';\n\n");
        call.append("export default class Call {\n\n");
        // Метод со статической регистрацией
        StringBuilder registerPackets = new StringBuilder();
        registerPackets.append("\tpublic static registerPackets(): void {\n");

        for (String anImport : imports) {
            packets.append(anImport).append('\n');
        }

        packets.append('\n');
        packets.append("export namespace Packets {\n");

        for (var e : packetToId) {
            String simpleName = e.key.getSimpleName();

            registerPackets.append("\t\tNet.registerPacket(p.").append(simpleName).append(", p.");
            registerPackets.append(simpleName).append(".ID);\n");

            generatePacket(packets, e.key, e.value);
        }

        registerPackets.append("\t}");
        call.append(registerPackets);
        call.append("\n}\n");

        packets.append("}\n");

        packets.close();
        call.close();

        System.out.println("unhandled types = " + types);
    }

    static void generatePacket(Writer packets, Class<?> klass, int id) throws Throwable {
        String name = klass.getSimpleName();
        var fields = Arrays.stream(klass.getDeclaredFields())
                .filter(f -> !f.getName().equals("DATA"))
                .collect(Collectors.toList());
        boolean empty = fields.isEmpty(); // ради красивого форматирования

        packets.append('\n'); // мы префиксно пишем разделитель
        packets.append("\texport class ").append(name).append(" implements Packet {\n");
        packets.append("\t\tpublic static ID = ").append(Integer.toString(id)).append(";\n\n");

        StringJoiner params = new StringJoiner(", ");
        StringBuilder assignments = new StringBuilder();

        StringBuilder serializer = new StringBuilder();
        serializer.append("\t\tserialize(buf: BufferWriter) {");

        StringBuilder deserializer = new StringBuilder();
        deserializer.append("\t\tdeserialize(buf: BufferReader) {");

        if (!empty) {
            serializer.append('\n');
            deserializer.append('\n');
        } else {
            serializer.append("}\n\n");
            deserializer.append('}');
        }

        int line = 0;
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            String mapType = mapType(field.getGenericType());

            String param = field.getName() + ": " + mapType;
            String lw;
            if (line + param.length() >= lineWrap) {
                lw = "\n\t\t\t\t";
                line = 0;
            } else {
                lw = "";
            }

            params.add(lw + param);
            line += param.length();

            // сериализация
            serializer.append("\t\t\tbuf.").append(serializerMethod(field.getType(), klass)).append("(this.");
            serializer.append(field.getName()).append(");\n");

            // десериализация
            deserializer.append("\t\t\tthis.").append(field.getName()).append(" = buf.");
            deserializer.append(deserializerMethod(field.getType(), klass)).append("();\n");

            assignments.append("\t\t\tthis.").append(field.getName()).append(" = args[").append(i).append("];\n");
            packets.append("\t\tpublic ").append(field.getName()).append(": ").append(mapType).append(";\n");
        }

        if (!empty) {
            packets.append('\n');

            // конструктор без параметров
            packets.append("\t\tconstructor();\n\n");

            // и конструктор со всеми параметрами
            packets.append("\t\tconstructor(").append(params.toString()).append(");\n\n");

            // собственно реализация конструктора
            packets.append("\t\tconstructor(...args: any[]) {\n");
            packets.append("\t\t\tif (args.length == 0) return;\n");
            packets.append(assignments);
            packets.append("\t\t}\n\n");
        }

        // getId(): number метод
        packets.append("\t\tgetId() {\n");
        packets.append("\t\t\treturn ").append(name).append(".ID;\n");
        packets.append("\t\t}\n\n");

        if (!empty) {
            serializer.append("\t\t}\n\n");
            deserializer.append("\t\t}");
        }

        packets.append(serializer);

        packets.append(deserializer);
        packets.append("\n\t}\n");
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
