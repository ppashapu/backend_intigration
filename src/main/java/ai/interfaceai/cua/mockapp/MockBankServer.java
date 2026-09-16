package ai.interfaceai.cua.mockapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A deliberately "legacy" back-office banking UI stand-in:
 *  - frameset-based navigation (search frame + results frame)
 *  - server-rendered, table-based layout, no data-testid / no classes
 *  - simulated business outcomes ("member not found") and transient
 *    runtime errors ("system busy" that clears on retry) to exercise
 *    the error taxonomy the replay engine has to implement.
 *
 * This is a stand-in for a real core-banking servicing screen per the
 * assignment's ground rules (never given access to a real bank system).
 */
public class MockBankServer {

    private final Map<String, Member> members = new HashMap<>();
    private final Map<String, Integer> transientAttempts = new ConcurrentHashMap<>();
    private final int port;
    private HttpServer server;

    public MockBankServer(int port) {
        this.port = port;
        seed();
    }

    private void seed() {
        members.put("12345", new Member("12345", "Jordan Alvarez", "Savings", 4210.55));
        members.put("40000", new Member("40000", "Priya Natarajan", "Checking", 980.10));
        // 99999 intentionally absent -> business outcome "not found"
        // 50000 intentionally absent from DB but handled specially -> transient "system busy" then a real record
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::routeLogin);
        server.createContext("/search", this::routeSearchFrameset);
        server.createContext("/search/form", this::routeSearchForm);
        server.createContext("/search/results", this::routeSearchResults);
        server.createContext("/member/view", this::routeMemberView);
        server.createContext("/member/newsubaccount", this::routeNewSubAccountForm);
        server.createContext("/member/newsubaccount/confirm", this::routeConfirm);
        server.createContext("/member/newsubaccount/submit", this::routeSubmit);
        server.setExecutor(null);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ---- routes ----

    private void routeLogin(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            redirect(ex, "/search");
            return;
        }
        html(ex, """
            <html><head><title>CoreServ Legacy Banking Console</title></head>
            <body>
            <h2>CoreServ Operator Login</h2>
            <form method="POST" action="/">
            <table border="0">
            <tr><td>Username</td><td><input type="text" name="u"></td></tr>
            <tr><td>Password</td><td><input type="password" name="p"></td></tr>
            <tr><td colspan="2"><input type="submit" value="Log In"></td></tr>
            </table>
            </form>
            </body></html>
            """);
    }

    private void routeSearchFrameset(HttpExchange ex) throws IOException {
        html(ex, """
            <html><head><title>Member Search</title></head>
            <frameset rows="120,*">
              <frame src="/search/form" name="searchForm">
              <frame src="about:blank" name="resultsFrame">
            </frameset>
            </html>
            """);
    }

    private void routeSearchForm(HttpExchange ex) throws IOException {
        html(ex, """
            <html><body>
            <form method="GET" action="/search/results" target="resultsFrame">
            <table border="0"><tr>
              <td>Member ID</td>
              <td><input type="text" name="memberId"></td>
              <td><input type="submit" value="Search"></td>
            </tr></table>
            </form>
            </body></html>
            """);
    }

    private void routeSearchResults(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String memberId = q.getOrDefault("memberId", "");

        if ("50000".equals(memberId)) {
            int attempts = transientAttempts.merge(memberId, 1, Integer::sum);
            if (attempts == 1) {
                html(ex, """
                    <html><body>
                    <p>SYSTEM BUSY — please retry the search shortly.</p>
                    </body></html>
                    """);
                return;
            }
            members.putIfAbsent("50000", new Member("50000", "Taylor Kim", "Savings", 150.00));
        }

        Member m = members.get(memberId);
        if (m == null) {
            html(ex, """
                <html><body>
                <table border="1">
                <tr><td>No records found for member ID: %s</td></tr>
                </table>
                </body></html>
                """.formatted(escape(memberId)));
            return;
        }
        html(ex, """
            <html><body>
            <table border="1">
            <tr><td>Member ID</td><td>Name</td><td>Action</td></tr>
            <tr><td>%s</td><td>%s</td><td><a href="/member/view?id=%s" target="_top">View</a></td></tr>
            </table>
            </body></html>
            """.formatted(escape(m.id), escape(m.name), escape(m.id)));
    }

    private void routeMemberView(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        Member m = members.get(q.get("id"));
        if (m == null) { notFound(ex); return; }
        html(ex, """
            <html><body>
            <h3>Member Detail</h3>
            <table border="1">
            <tr><td>Name</td><td>%s</td></tr>
            <tr><td>Account Type</td><td>%s</td></tr>
            <tr><td>Current Balance</td><td id="bal">$%,.2f</td></tr>
            </table>
            <p><a href="/member/newsubaccount?id=%s">Open Sub-Account</a></p>
            </body></html>
            """.formatted(escape(m.name), escape(m.accountType), m.balance, escape(m.id)));
    }

    private void routeNewSubAccountForm(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        String id = q.get("id");
        if (members.get(id) == null) { notFound(ex); return; }
        html(ex, """
            <html><body>
            <h3>Open Sub-Account for member %s</h3>
            <form method="POST" action="/member/newsubaccount/confirm">
            <input type="hidden" name="id" value="%s">
            <table border="0">
            <tr><td>Account Type</td><td>
              <select name="acctType">
                <option value="Savings">Savings</option>
                <option value="CD">Certificate of Deposit</option>
              </select>
            </td></tr>
            <tr><td>Initial Deposit ($)</td><td><input type="text" name="deposit"></td></tr>
            <tr><td colspan="2"><input type="submit" value="Continue"></td></tr>
            </table>
            </form>
            </body></html>
            """.formatted(escape(id), escape(id)));
    }

    private void routeConfirm(HttpExchange ex) throws IOException {
        Map<String, String> form = parseFormBody(ex);
        String id = form.get("id");
        String acctType = form.getOrDefault("acctType", "");
        double deposit = parseDoubleSafe(form.get("deposit"));

        if (deposit < 25.0) {
            html(ex, """
                <html><body>
                <p>VALIDATION ERROR: Initial deposit must be at least $25.00.</p>
                <p><a href="/member/newsubaccount?id=%s">Back</a></p>
                </body></html>
                """.formatted(escape(id)));
            return;
        }

        html(ex, """
            <html><body>
            <h3>Confirm New Sub-Account</h3>
            <table border="1">
            <tr><td>Member</td><td>%s</td></tr>
            <tr><td>Account Type</td><td>%s</td></tr>
            <tr><td>Initial Deposit</td><td>$%.2f</td></tr>
            </table>
            <form method="POST" action="/member/newsubaccount/submit">
              <input type="hidden" name="id" value="%s">
              <input type="hidden" name="acctType" value="%s">
              <input type="hidden" name="deposit" value="%.2f">
              <input type="submit" value="Confirm and Open Account">
            </form>
            </body></html>
            """.formatted(escape(id), escape(acctType), deposit, escape(id), escape(acctType), deposit));
    }

    private void routeSubmit(HttpExchange ex) throws IOException {
        Map<String, String> form = parseFormBody(ex);
        String id = form.get("id");
        String newAcctNum = "SUB-" + id + "-" + (1000 + new Random().nextInt(9000));
        html(ex, """
            <html><body>
            <h3>Sub-Account Created</h3>
            <table border="1">
            <tr><td>New Account Number</td><td id="newAcct">%s</td></tr>
            <tr><td>Status</td><td>ACTIVE</td></tr>
            </table>
            </body></html>
            """.formatted(escape(newAcctNum)));
    }

    // ---- helpers ----

    private void notFound(HttpExchange ex) throws IOException {
        html(ex, "<html><body><table border=\"1\"><tr><td>No records found.</td></tr></table></body></html>");
    }

    private void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void html(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(urlDecode(kv[0]), urlDecode(kv[1]));
            }
        }
        return map;
    }

    private Map<String, String> parseFormBody(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseQuery(body);
    }

    private String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Member(String id, String name, String accountType, double balance) {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8085;
        MockBankServer s = new MockBankServer(port);
        s.start();
        System.out.println("MockBankServer running at http://127.0.0.1:" + port + "/");
    }
}
