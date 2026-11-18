package com.alpineagent;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Skills = pastas em files/skills/<nome>/SKILL.md (com "description:" no cabeçalho). */
class Skills {
    final File dir;

    Skills(Context c) {
        dir = new File(c.getFilesDir(), "skills");
        dir.mkdirs();
        String[] l = dir.list();
        if (l == null || l.length == 0) {
            try {
                write("alpine-pacotes", "---\nname: alpine-pacotes\ndescription: Como instalar pacotes no Alpine Linux do terminal\n---\n"
                    + "Use `apk add --no-cache <pacote>`. Exemplos: python3, curl, git, nodejs, openjdk17.\n");
            } catch (Exception ignored) {}
        }
    }

    static String clean(String n) { return n.replaceAll("[^a-zA-Z0-9_-]", ""); }

    String index() {
        StringBuilder sb = new StringBuilder();
        File[] fs = dir.listFiles();
        if (fs == null) return "";
        for (File f : fs) {
            File s = new File(f, "SKILL.md");
            if (!s.exists()) continue;
            String desc = "";
            try {
                for (String line : new String(Files.readAllBytes(s.toPath()), StandardCharsets.UTF_8).split("\n"))
                    if (line.startsWith("description:")) { desc = line.substring(12).trim(); break; }
            } catch (Exception ignored) {}
            sb.append("- ").append(f.getName()).append(": ").append(desc).append('\n');
        }
        return sb.toString();
    }

    String read(String name) throws Exception {
        File s = new File(new File(dir, clean(name)), "SKILL.md");
        return s.exists() ? new String(Files.readAllBytes(s.toPath()), StandardCharsets.UTF_8) : "skill não encontrada";
    }

    void write(String name, String content) throws Exception {
        File d = new File(dir, clean(name));
        d.mkdirs();
        Files.write(new File(d, "SKILL.md").toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
