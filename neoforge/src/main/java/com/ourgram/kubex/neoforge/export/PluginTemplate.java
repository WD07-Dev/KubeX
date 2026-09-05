package com.ourgram.kubex.neoforge.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class PluginTemplate {
    public record PluginClass(String className, byte[] bytecode) {}

    private static final String RESOURCE = "com/ourgram/kubex/neoforge/export/template/ScriptPlugin.class";
    private static final String TEMPLATE_INTERNAL_NAME = "com/ourgram/kubex/neoforge/export/template/ScriptPlugin";
    private static final String MOD_ID_PLACEHOLDER = "__KUBEX_MOD_ID__";

    public PluginClass create(String modPackage, String modId) throws IOException {
        String className = modPackage + ".Plugin";
        return new PluginClass(className, rewriteConstants(templateBytes(), Map.of(
            TEMPLATE_INTERNAL_NAME, className.replace('.', '/'),
            MOD_ID_PLACEHOLDER, modId
        )));
    }

    private byte[] templateBytes() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if(input == null) throw new IOException("Missing export plugin template");
            return input.readAllBytes();
        }
    }

    private static byte[] rewriteConstants(byte[] source, Map<String, String> replacements) throws IOException {
        if(source.length < 10 || readU4(source, 0) != 0xCAFEBABE) throw new IOException("Invalid export plugin template");

        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length + 128);
        output.write(source, 0, 10);
        int constantPoolCount = readU2(source, 8);
        int offset = 10;

        for(int index = 1; index < constantPoolCount; index++) {
            int tag = readU1(source, offset++);
            output.write(tag);
            if(tag == 1) {
                int length = readU2(source, offset);
                offset += 2;
                String value = new String(source, offset, length, StandardCharsets.UTF_8);
                byte[] replacement = replacements.getOrDefault(value, value).getBytes(StandardCharsets.UTF_8);
                writeU2(output, replacement.length);
                output.write(replacement);
                offset += length;
                continue;
            }

            int length = constantLength(tag);
            output.write(source, offset, length);
            offset += length;
            if(tag == 5 || tag == 6) index++;
        }

        output.write(source, offset, source.length - offset);
        return output.toByteArray();
    }

    private static int constantLength(int tag) throws IOException {
        return switch(tag) {
            case 3, 4 -> 4;
            case 5, 6 -> 8;
            case 7, 8, 16, 19, 20 -> 2;
            case 9, 10, 11, 12, 17, 18 -> 4;
            case 15 -> 3;
            default -> throw new IOException("Unsupported class constant tag: " + tag);
        };
    }

    private static int readU1(byte[] source, int offset) {
        return Byte.toUnsignedInt(source[offset]);
    }

    private static int readU2(byte[] source, int offset) {
        return (readU1(source, offset) << 8) | readU1(source, offset + 1);
    }

    private static int readU4(byte[] source, int offset) {
        return (readU1(source, offset) << 24) | (readU1(source, offset + 1) << 16) | (readU1(source, offset + 2) << 8) | readU1(source, offset + 3);
    }

    private static void writeU2(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }
}