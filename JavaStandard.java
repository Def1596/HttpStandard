import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JavaStandard {
    private static final Gson GSON = new Gson();

    /**
     * Prevents creating JavaStandard as an object.
     * This class only groups utility classes and small HTTP/Servlet helpers.
     */
    private JavaStandard() {
    }

    // Shared helpers for parsing JSON into Map/List values and writing values back to JSON.
    public static final class JsonUtil {
        /**
         * Parses a JSON string into Java values.
         *
         * Objects become Map<String, Object>, arrays become List<Object>,
         * strings become String, and numbers become Long or Double.
         *
         * @param json JSON text received from a file, exe, or HTTP request body.
         * @return parsed Java value, or null when the input is empty.
         */
        public static Object parse(String json) {
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            return GSON.fromJson(json, Object.class);
        }

        /**
         * Converts a Java value into JSON text.
         *
         * This method supports null, String, Number, Boolean, Map, and Iterable.
         * Other objects are converted with String.valueOf and written as JSON strings.
         *
         * @param value Java value to write as JSON.
         * @return JSON string that can be sent back to the exe or another client.
         */
        public static String toJson(Object value) {
            return GSON.toJson(value);
        }

        /**
         * Creates a predictable insertion-ordered map.
         *
         * LinkedHashMap is used so response JSON keeps the order in which keys are added.
         *
         * @return empty Map<String, Object>.
         */
        public static Map<String, Object> newMap() {
            return new LinkedHashMap<String, Object>();
        }

        /**
         * Safely casts a parsed JSON value to Map<String, Object>.
         *
         * If the value is not a Map, an empty map is returned instead of throwing an error.
         * This keeps servlet code simpler when request data is missing or malformed.
         *
         * @param value parsed JSON value.
         * @return value as a map, or an empty map.
         */
        @SuppressWarnings("unchecked")
        public static Map<String, Object> toMap(Object value) {
            return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
        }

        /**
         * Converts a JSON object-like value into Map<String, String>.
         *
         * Null values become empty strings. Non-string values are converted with String.valueOf.
         * This is useful when saving request records as simple key/value text.
         *
         * @param value parsed JSON object.
         * @return string map, or an empty map when value is not an object.
         */
        public static Map<String, String> toStringMap(Object value) {
            Map<String, Object> map = toMap(value);
            if (map.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, String> result = new LinkedHashMap<String, String>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
            return result;
        }

        /**
         * Reads a nested value from parsed JSON using dot and array-index paths.
         *
         * Example:
         * path(root, "user.name") reads root.user.name.
         * path(root, "items[0].id") reads the id from the first item.
         *
         * @param root parsed JSON root object.
         * @param path dot-separated path with optional [index] parts.
         * @return found value, or null when any part is missing.
         */
        public static Object path(Object root, String path) {
            if (path == null || path.isEmpty()) {
                return root;
            }

            // Supports paths such as "user.name" and "items[0].id".
            Object value = root;
            for (String token : path.split("\\.")) {
                if (token.isEmpty()) {
                    continue;
                }
                value = nextValue(value, token);
                if (value == null) {
                    return null;
                }
            }
            return value;
        }

        /**
         * Reads a nested value and converts it to String.
         *
         * This is used by servlet code for fields like testId and time, where the server
         * only needs text regardless of whether the JSON value was a string or number.
         *
         * @param root parsed JSON root object.
         * @param path field path to read.
         * @return string value, or null when the path is missing.
         */
        public static String stringValue(Object root, String path) {
            Object value = path(root, path);
            return value == null ? null : String.valueOf(value);
        }

        /**
         * Resolves one path token against the current JSON value.
         *
         * A token can contain only a key, such as "user", or a key plus indexes,
         * such as "items[0]". Invalid maps, invalid indexes, or missing values return null.
         *
         * @param current current object while walking the path.
         * @param token one path token from the full path.
         * @return next value in the path, or null.
         */
        private static Object nextValue(Object current, String token) {
            String key = token;
            int firstBracket = token.indexOf('[');
            if (firstBracket >= 0) {
                key = token.substring(0, firstBracket);
            }

            if (!key.isEmpty()) {
                if (!(current instanceof Map)) {
                    return null;
                }
                current = ((Map<?, ?>) current).get(key);
            }

            int indexStart = token.indexOf('[');
            while (indexStart >= 0) {
                int indexEnd = token.indexOf(']', indexStart);
                if (indexEnd < 0 || !(current instanceof List)) {
                    return null;
                }
                int index = Integer.parseInt(token.substring(indexStart + 1, indexEnd));
                List<?> list = (List<?>) current;
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
                indexStart = token.indexOf('[', indexEnd);
            }

            return current;
        }

    }

    // Reads small text files such as test.JSON.
    public static final class FileUtil {
        /**
         * Reads a UTF-8 file into a String.
         *
         * When the file does not exist, "{}" is returned so startup can continue with
         * an empty JSON object instead of failing immediately.
         *
         * @param path file path to read.
         * @return UTF-8 text, or "{}" when the file is missing.
         * @throws IOException when the file exists but cannot be read.
         */
        public static String readString(Path path) throws IOException {
            if (!Files.exists(path)) {
                return "{}";
            }
            byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    // Request wrapper that lets the JDK HttpExchange look like a small servlet request.
    public static class HttpServletRequest {
        private final HttpExchange exchange;

        /**
         * Wraps the raw JDK HttpExchange request.
         *
         * @param exchange HTTP exchange created by HttpServer.
         */
        HttpServletRequest(HttpExchange exchange) {
            this.exchange = exchange;
        }

        /**
         * Returns the HTTP method used by the client.
         *
         * @return method such as GET or POST.
         */
        public String getMethod() {
            return exchange.getRequestMethod();
        }

        /**
         * Returns the request path without query string.
         *
         * @return path such as /test or /test2.
         */
        public String getPath() {
            return exchange.getRequestURI().getPath();
        }

        /**
         * Returns all request headers.
         *
         * @return JDK Headers object from the request.
         */
        public Headers getHeaders() {
            return exchange.getRequestHeaders();
        }

        /**
         * Gives internal helpers access to the raw exchange.
         *
         * This is package-private on purpose so normal servlet code uses the wrapper methods.
         *
         * @return raw HttpExchange.
         */
        HttpExchange exchange() {
            return exchange;
        }
    }

    // Response wrapper that lets the JDK HttpExchange look like a small servlet response.
    public static class HttpServletResponse {
        private final HttpExchange exchange;
        private int status = 200;

        /**
         * Wraps the raw JDK HttpExchange response side.
         *
         * @param exchange HTTP exchange created by HttpServer.
         */
        HttpServletResponse(HttpExchange exchange) {
            this.exchange = exchange;
        }

        /**
         * Sets the HTTP status code that will be used when the response is sent.
         *
         * @param status status code such as 200, 400, 404, or 500.
         */
        public void setStatus(int status) {
            this.status = status;
        }

        /**
         * Sets the Content-Type response header.
         *
         * @param contentType MIME type and charset, for example application/json; charset=UTF-8.
         */
        public void setContentType(String contentType) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }

        /**
         * Sets the Content-Length response header.
         *
         * @param length number of bytes in the response body.
         */
        public void setContentLength(int length) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(length));
        }

        /**
         * Returns the raw response stream.
         *
         * Most code should use ServletUtil.sendJson instead, because it sets headers and closes
         * the stream correctly.
         *
         * @return response output stream.
         */
        public OutputStream getOutputStream() {
            return exchange.getResponseBody();
        }

        /**
         * Sends a response with no body.
         *
         * Used for status-only responses like 405 Method Not Allowed.
         *
         * @throws IOException when the client connection cannot be written.
         */
        public void flushBuffer() throws IOException {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        /**
         * Sends response bytes and closes the output stream.
         *
         * @param bytes already-encoded response body.
         * @throws IOException when the client connection cannot be written.
         */
        void sendBytes(byte[] bytes) throws IOException {
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream output = exchange.getResponseBody();
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
        }
    }

    // Base servlet: implement doGet or doPost and service routes the request by method.
    public static abstract class HttpServlet {
        /**
         * Handles GET requests.
         *
         * Child servlet classes override this when they want to support GET.
         * The default behavior is 405 because this server should not silently accept
         * unsupported methods.
         *
         * @param request wrapped request.
         * @param response wrapped response.
         * @throws Exception allows servlet implementations to throw simple errors.
         */
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws Exception {
            ServletUtil.sendStatus(response, 405);
        }

        /**
         * Handles POST requests.
         *
         * Child servlet classes override this when they want to receive JSON from an exe
         * or another HTTP client. Default behavior is 405.
         *
         * @param request wrapped request.
         * @param response wrapped response.
         * @throws Exception allows servlet implementations to throw simple errors.
         */
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws Exception {
            ServletUtil.sendStatus(response, 405);
        }

        /**
         * Dispatches the raw HttpExchange to doGet or doPost.
         *
         * This method centralizes request wrapping and error handling. If servlet code throws
         * an exception, the client receives a JSON error response with status 500.
         *
         * @param exchange raw exchange from HttpServer.
         * @throws IOException when the response cannot be written.
         */
        void service(HttpExchange exchange) throws IOException {
            HttpServletRequest request = new HttpServletRequest(exchange);
            HttpServletResponse response = new HttpServletResponse(exchange);
            try {
                // When an exe or client sends JSON with POST, doPost is executed.
                if ("POST".equalsIgnoreCase(request.getMethod())) {
                    doPost(request, response);
                } else {
                    doGet(request, response);
                }
            } catch (Exception e) {
                Map<String, Object> error = JsonUtil.newMap();
                error.put("ok", false);
                error.put("error", e.getMessage());
                ServletUtil.sendJson(response, 500, error);
            }
        }
    }

    // Common servlet helpers for reading request bodies and sending JSON responses.
    public static final class ServletUtil {
        /**
         * Reads the full request body as UTF-8 text.
         *
         * This is the raw body reader used before JSON parsing. It is useful when an exe
         * posts a JSON string to /test.
         *
         * @param request wrapped servlet request.
         * @return request body as text.
         * @throws IOException when the request stream cannot be read.
         */
        public static String readBody(HttpServletRequest request) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = request.exchange().getRequestBody().read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }

        /**
         * Reads the request body and parses it as JSON.
         *
         * Empty bodies become an empty map so callers do not need to null-check before
         * reading fields.
         *
         * @param request wrapped servlet request.
         * @return parsed JSON value.
         * @throws IOException when the request stream cannot be read.
         */
        public static Object readJsonBody(HttpServletRequest request) throws IOException {
            String body = readBody(request);
            return body.trim().isEmpty() ? JsonUtil.newMap() : JsonUtil.parse(body);
        }

        /**
         * Sends a response with only an HTTP status code and no body.
         *
         * @param response wrapped response.
         * @param status HTTP status code.
         * @throws IOException when the response cannot be written.
         */
        public static void sendStatus(HttpServletResponse response, int status) throws IOException {
            response.setStatus(status);
            response.flushBuffer();
        }

        /**
         * Sends a JSON response with status 200.
         *
         * @param response wrapped response.
         * @param body Java value to serialize as JSON.
         * @throws IOException when the response cannot be written.
         */
        public static void sendJson(HttpServletResponse response, Object body) throws IOException {
            sendJson(response, 200, body);
        }

        /**
         * Sends a JSON response with a custom HTTP status.
         *
         * The body is encoded as UTF-8, Content-Type is set to application/json,
         * and the response stream is closed after writing.
         *
         * @param response wrapped response.
         * @param status HTTP status code.
         * @param body Java value to serialize as JSON.
         * @throws IOException when the response cannot be written.
         */
        public static void sendJson(HttpServletResponse response, int status, Object body) throws IOException {
            byte[] bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
            response.setStatus(status);
            response.setContentType("application/json; charset=UTF-8");
            response.setContentLength(bytes.length);
            response.sendBytes(bytes);
        }
    }

    // Registers servlet-style handlers on the JDK HttpServer and starts it.
    public static final class ServerUtil {
        /**
         * Creates a JDK HttpServer bound to all network interfaces.
         *
         * Binding to 0.0.0.0 allows a local exe and other machines on the network
         * to connect if firewall rules allow it.
         *
         * @param port server port, for example 8080.
         * @return created but not yet started HttpServer.
         * @throws IOException when the port cannot be opened.
         */
        public static HttpServer createServer(int port) throws IOException {
            return HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        }

        /**
         * Creates a context object that will hold servlet mappings.
         *
         * @return empty ServletContextHandler.
         */
        public static ServletContextHandler createRootContext() {
            return new ServletContextHandler();
        }

        /**
         * Adds a servlet to a path.
         *
         * For example, addServlet(context, "/test", servlet) makes that servlet handle
         * requests sent to http://localhost:8080/test.
         *
         * @param context context that stores mappings before server start.
         * @param path URL path.
         * @param servlet servlet instance that handles the path.
         */
        public static void addServlet(ServletContextHandler context, String path, HttpServlet servlet) {
            context.addServlet(path, servlet);
        }

        /**
         * Registers all context mappings on the server and starts listening.
         *
         * Unlike Jetty's join, the JDK HttpServer uses non-daemon threads after start,
         * so the program keeps running without a separate join call.
         *
         * @param server created HttpServer.
         * @param context servlet mappings to register.
         */
        public static void startAndJoin(HttpServer server, ServletContextHandler context) {
            for (Map.Entry<String, HttpServlet> entry : context.servlets.entrySet()) {
                final HttpServlet servlet = entry.getValue();
                server.createContext(entry.getKey(), new HttpHandler() {
                    public void handle(HttpExchange exchange) throws IOException {
                        servlet.service(exchange);
                    }
                });
            }
            server.start();
            System.out.println("server started: http://localhost:" + server.getAddress().getPort());
        }
    }

    // Small context object that keeps path-to-servlet mappings.
    public static final class ServletContextHandler {
        private final Map<String, HttpServlet> servlets = new LinkedHashMap<String, HttpServlet>();

        /**
         * Saves a path-to-servlet mapping.
         *
         * @param path URL path such as /test.
         * @param servlet servlet that should handle requests for the path.
         */
        public void addServlet(String path, HttpServlet servlet) {
            servlets.put(path, servlet);
        }
    }

    // Runs an external exe or command and captures its output when needed.
    public static final class ProcessUtil {
        /**
         * Runs a command and returns only the exit code.
         *
         * Use this when the server only needs to know whether the external exe succeeded.
         *
         * @param workDir working directory for the process.
         * @param command command and arguments, for example "tool.exe", "--input", "a.json".
         * @return process exit code.
         * @throws Exception when the process cannot be started or interrupted.
         */
        public static int runCommand(String workDir, String... command) throws Exception {
            ProcessResult result = runCommandWithOutput(workDir, command);
            return result.exitCode;
        }

        /**
         * Runs a command and captures merged stdout/stderr output.
         *
         * redirectErrorStream(true) is used so error messages and normal output arrive
         * in one string, which makes debugging exe communication easier.
         *
         * @param workDir working directory for the process.
         * @param command command and arguments.
         * @return exit code plus captured output.
         * @throws Exception when the process cannot be started or interrupted.
         */
        public static ProcessResult runCommandWithOutput(String workDir, String... command) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(workDir));
            builder.redirectErrorStream(true);
            Process process = builder.start();

            StringBuilder output = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } finally {
                reader.close();
            }

            return new ProcessResult(process.waitFor(), output.toString());
        }
    }

    // Result from an external process execution.
    public static final class ProcessResult {
        public final int exitCode;
        public final String output;

        /**
         * Stores process execution result values.
         *
         * @param exitCode process exit code.
         * @param output captured stdout/stderr text.
         */
        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

}
