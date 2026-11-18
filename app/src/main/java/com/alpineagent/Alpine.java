package com.alpineagent;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Alpine Linux (minirootfs) rodando via proot, sem root. */
class Alpine {
    static final String ROOTFS_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz";
    final Context ctx;
    final File root, proot, tmp;

    Alpine(Context c) {
        ctx = c;
        root = new File(c.getFilesDir(), "alpine");
        proot = new File(c.getFilesDir(), "proot");
        tmp = new File(c.getFilesDir(), "tmp");
        tmp.mkdirs();
    }

    boolean installed() { return new File(root, "bin/busybox").exists(); }

    boolean hasProot() {
        if (!proot.exists()) {
            try (InputStream i = ctx.getAssets().open("proot"); OutputStream o = new FileOutputStream(proot)) {
                byte[] b = new byte[65536]; int n;
                while ((n = i.read(b)) > 0) o.write(b, 0, n);
            } catch (IOException e) { return false; }
        }
        proot.setExecutable(true, false);
        return proot.exists();
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toString("UTF-8");
    }

    void install(Agent.Log log) throws Exception {
        root.mkdirs();
        File tgz = new File(ctx.getCacheDir(), "rootfs.tar.gz");
        log.log("Baixando Alpine...\n");
        HttpURLConnection c = (HttpURLConnection) new URL(ROOTFS_URL).openConnection();
        try (InputStream i = c.getInputStream(); OutputStream o = new FileOutputStream(tgz)) {
            byte[] b = new byte[65536]; int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
        }
        log.log("Extraindo...\n");
        Process p = new ProcessBuilder("/system/bin/tar", "-xzf", tgz.getAbsolutePath(), "-C", root.getAbsolutePath())
            .redirectErrorStream(true).start();
        String out = readAll(p.getInputStream());
        p.waitFor();
        new File(root, "etc").mkdirs();
        try (FileWriter w = new FileWriter(new File(root, "etc/resolv.conf"))) {
            w.write("nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        }
        tgz.delete();
        log.log(installed()
            ? "Alpine instalado. proot: " + (hasProot() ? "ok" : "AUSENTE (veja README)") + "\n"
            : "Falha na instalação: " + out + "\n");
    }

    /** Inicia um processo dentro do Alpine (stderr junto com stdout). */
    Process spawn(String... inner) throws IOException { return open(true, inner); }

    /**
     * Inicia um processo dentro do Alpine (ou no sh do Android se o Alpine/proot não existir).
     * Argumentos iniciais no formato CHAVE=VALOR viram variáveis de ambiente.
     * mergeErr=false mantém stderr separado (necessário para MCP stdio, onde stdout é só JSON-RPC).
     */
    Process open(boolean mergeErr, String... inner) throws IOException {
        ProcessBuilder pb;
        if (installed() && hasProot()) {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                proot.getPath(), "-0", "--link2symlink", "-r", root.getPath(),
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root",
                "/usr/bin/env", "-i", "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color"));
            cmd.addAll(Arrays.asList(inner));
            pb = new ProcessBuilder(cmd);
            pb.environment().put("PROOT_TMP_DIR", tmp.getPath());
            pb.environment().put("PROOT_NO_SECCOMP", "1");
        } else {
            List<String> cmd = new ArrayList<>();
            Map<String, String> env = new HashMap<>();
            boolean head = true;
            for (String a : inner) {
                if (head && a.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) { env.put(a.substring(0, a.indexOf('=')), a.substring(a.indexOf('=') + 1)); continue; }
                head = false;
                cmd.add(a);
            }
            pb = new ProcessBuilder(cmd);
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(mergeErr);
        return pb.start();
    }

    String exec(String cmd, int timeoutSec) throws Exception {
        Process p = spawn("/bin/sh", "-c", cmd);
        StringBuffer sb = new StringBuffer();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) if (sb.length() < 8000) sb.append(l).append('\n');
            } catch (IOException ignored) {}
        });
        t.start();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); sb.append("[timeout]"); }
        t.join(1000);
        return sb.length() == 0 ? "(sem saída)" : sb.toString();
    }
}
