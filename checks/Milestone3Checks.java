package com.s1ambient;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises actual portable production timing + HTTP server classes, no Android emulator. */
public final class Milestone3Checks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String exchange(int port, String request) throws Exception {
        try(Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    public static void main(String[] args) throws Exception {
        PairingGate gate = new PairingGate();
        check(gate.pair("abc", "abc", 0) == 200, "Correct pairing code");
        for(int i=0;i<5;i++) check(gate.pair("bad", "abc", 1000) == 401, "Wrong code rejected");
        check(gate.pair("abc", "abc", 2000) == 429, "Global brute-force cooldown");
        check(gate.pair("abc", "abc", 61000) == 200, "Cooldown expires");
        check(!PairingGate.Companion.equal("old-token", "new-token"), "Revoked token rejected");
        check(PairingGate.Companion.equal("Bearer token", "Bearer token"), "Bearer comparison");
        TimeState t = new TimeState();
        t.timer("set", 1000, 10L); t.timer("start", 1000, null);
        check(t.remaining(6500) == 4500, "Countdown must derive from timestamp");
        t.timer("pause", 6500, null);
        check(t.remaining(900000) == 4500, "Pause must freeze remaining time");
        t.timer("resume", 1000000, null);
        check(t.remaining(1004000) == 500, "Resume preserves remaining time");
        check(t.finish(1004500), "Timer expires at deadline without ticks");
        check(!t.finish(1009999), "Completion must fire once");
        t.timer("reset", 0, null);
        check(t.getTimerMode().equals("idle") && t.remaining(0) == 10000, "Reset restores duration");
        t.timer("start", 0, null); t.timer("stop", 1000, null);
        check(t.getTimerMode().equals("idle") && t.remaining(3000) == 10000, "Stop cancels timer");
        try { t.timer("set",0,0L); throw new AssertionError("Zero accepted"); } catch(IllegalArgumentException expected) {}
        try { t.timer("set",0,86401L); throw new AssertionError("Too long accepted"); } catch(IllegalArgumentException expected) {}
        t.stopwatch("start",500); t.stopwatch("start",600); // repeated start is idempotent
        check(t.elapsed(10500) == 10000, "Stopwatch must not reset on duplicate start");
        t.stopwatch("pause",10500); check(t.elapsed(500000) == 10000, "Stopwatch pause");
        t.stopwatch("resume",500000); check(t.elapsed(503000) == 13000, "Stopwatch resume");
        t.stopwatch("reset",503000); check(t.elapsed(999999) == 0 && !t.getWatchRunning(), "Stopwatch reset");

        int port;
        try(ServerSocket reserve = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port=reserve.getLocalPort(); }
        final int chosenPort=port;
        Map<String,HttpResponse> pages = new HashMap<>();
        pages.put("/", new HttpResponse(200,"text/html",Files.readAllBytes(Path.of("app/src/main/assets/remote/index.html"))));
        pages.put("/app.js", new HttpResponse(200,"text/javascript",Files.readAllBytes(Path.of("app/src/main/assets/remote/app.js"))));
        pages.put("/app.css", new HttpResponse(200,"text/css",Files.readAllBytes(Path.of("app/src/main/assets/remote/app.css"))));
        AtomicInteger dispatches = new AtomicInteger();
        LocalHttpServer server = new LocalHttpServer(InetAddress.getByName("127.0.0.1"),port,pages,request -> {
            dispatches.incrementAndGet();
            return HttpResponse.Companion.json(200,"{\"method\":\""+request.getMethod()+"\"}");
        });
        server.start();
        try {
            String host="Host: 127.0.0.1:"+port+"\r\n";
            String response=exchange(port,"GET / HTTP/1.1\r\n"+host+"\r\n");
            check(response.startsWith("HTTP/1.1 200") && response.contains("S1 Ambient"),"Bundled web page");
            check(response.contains("Content-Security-Policy:") && response.contains("X-Frame-Options: DENY"),"Browser protections");
            check(exchange(port,"GET /app.js HTTP/1.1\r\n"+host+"\r\n").contains("setInterval"),"Bundled JS");
            check(exchange(port,"GET /api/status HTTP/1.1\r\n"+host+"\r\n").startsWith("HTTP/1.1 200"),"API dispatch");
            int calls=dispatches.get();
            check(exchange(port,"GET /api/status HTTP/1.1\r\nHost: attacker.example\r\n\r\n").startsWith("HTTP/1.1 403"),"DNS rebinding host rejection");
            check(exchange(port,"POST /api/tasks HTTP/1.1\r\n"+host+"Origin: http://attacker.example\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}").startsWith("HTTP/1.1 403"),"Cross-origin rejection");
            check(exchange(port,"POST /api/tasks HTTP/1.1\r\n"+host+"Content-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}").startsWith("HTTP/1.1 415"),"Simple cross-site content type rejection");
            check(exchange(port,"POST /api/tasks HTTP/1.1\r\n"+host+"Content-Type: application/json\r\nContent-Length: 16385\r\n\r\n").startsWith("HTTP/1.1 413"),"Body limit");
            check(exchange(port,"POST /api/tasks HTTP/1.1\r\n"+host+"Content-Type: application/json\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}").startsWith("HTTP/1.1 400"),"Duplicate header rejection");
            check(exchange(port,"POST /api/tasks HTTP/1.1\r\n"+host+"Transfer-Encoding: chunked\r\n\r\n").startsWith("HTTP/1.1 400"),"Chunked request rejection");
            check(exchange(port,"GET /../../local.properties HTTP/1.1\r\n"+host+"\r\n").startsWith("HTTP/1.1 404"),"No file traversal");
            check(dispatches.get()==calls,"Invalid requests must not reach app state");
            check(exchange(port,"POST /api/tasks HTTP/1.1\r\n"+host+"Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}").startsWith("HTTP/1.1 200"),"Valid JSON request dispatch");
        } finally { server.close(); }
        LocalHttpServer replacement = new LocalHttpServer(InetAddress.getByName("127.0.0.1"),chosenPort,pages,r->HttpResponse.Companion.json(200,"{}"));
        replacement.start(); replacement.close();
        System.out.println("Passed: pairing/token/cooldown, timer/stopwatch transitions, delayed UI, duplicate start, completion once, HTTP assets/dispatch, request limits, origin/host rejection, traversal rejection, server stop/rebind.");
    }
}
