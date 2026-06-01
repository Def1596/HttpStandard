import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class run {
    // Base data loaded from test.JSON. Used to match the testId sent by an exe.
    private static Map<String, List<String>> modelMap = Collections.emptyMap();

    // Keeps records received through /test in memory.
    private static final Map<String, Map<String, String>> records = new LinkedHashMap<String, Map<String, String>>();

    /**
     * Starts the HTTP server.
     *
     * Startup flow:
     * 1. Read test.JSON into modelMap.
     * 2. Create a server on port 8080.
     * 3. Register /test for incoming exe data.
     * 4. Register /test2 for checking stored server state.
     *
     * @param args currently unused.
     */
    public static void main(String[] args) {
        try {
            // Load JSON data before starting the server.
            modelMap = loadModels();

            HttpServer server = JavaStandard.ServerUtil.createServer(8080);
            JavaStandard.ServletContextHandler context = JavaStandard.ServerUtil.createRootContext();

            // An exe can send JSON to /test and check current state through /test2.
            JavaStandard.ServerUtil.addServlet(context, "/test", new TestServlet());
            JavaStandard.ServerUtil.addServlet(context, "/test2", new Test2Servlet());
            JavaStandard.ServerUtil.startAndJoin(server, context);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Reads test.JSON and converts it into Map<String, List<String>>.
    /**
     * Loads base model data from test.JSON.
     *
     * Expected JSON shape:
     * {
     *   "test01": ["model-a"],
     *   "sample": ["agent-a", "agent-b"]
     * }
     *
     * @return map from testId to a list of related model/agent values.
     * @throws Exception when the file exists but cannot be read or parsed.
     */
    private static Map<String, List<String>> loadModels() throws Exception {
        String json = JavaStandard.FileUtil.readString(Paths.get("test.JSON"));
        Map<String, Object> root = JavaStandard.JsonUtil.toMap(JavaStandard.JsonUtil.parse(json));
        if (root.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<String>> result = new HashMap<String, List<String>>();
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            result.put(entry.getKey(), toStringList(entry.getValue()));
        }
        return result;
    }

    // Converts JSON arrays to string lists, and single values to one-item lists.
    /**
     * Normalizes a JSON value into a List<String>.
     *
     * This allows test.JSON values to be either arrays or single values while the rest
     * of the code always works with List<String>.
     *
     * @param value parsed JSON value from test.JSON.
     * @return list of string values.
     */
    private static List<String> toStringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                result.add(item == null ? "" : String.valueOf(item));
            }
        } else if (value != null) {
            result.add(String.valueOf(value));
        }
        return result;
    }

    // Servlet that receives JSON data sent by an exe with POST /test.
    private static class TestServlet extends JavaStandard.HttpServlet {
        /**
         * Receives JSON from an exe and stores it in memory.
         *
         * Required field:
         * - testId: used as the record key and to look up modelMap.
         *
         * Optional field:
         * - time: saved with the record when present.
         *
         * @param request servlet request containing a JSON body.
         * @param response servlet response used to send JSON result.
         * @throws IOException when request or response streams fail.
         */
        @Override
        protected void doPost(JavaStandard.HttpServletRequest request,
                              JavaStandard.HttpServletResponse response) throws IOException {
            // Example request body: {"testId":"test01","time":"2026-06-01T23:55:00","value":123}
            Map<String, Object> data = JavaStandard.JsonUtil.toMap(JavaStandard.ServletUtil.readJsonBody(request));
            String testId = JavaStandard.JsonUtil.stringValue(data, "testId");
            String time = JavaStandard.JsonUtil.stringValue(data, "time");

            if (testId == null || testId.length() == 0) {
                Map<String, Object> error = JavaStandard.JsonUtil.newMap();
                error.put("ok", false);
                error.put("message", "testId is required");
                JavaStandard.ServletUtil.sendJson(response, 400, error);
                return;
            }

            // If the same testId arrives again, update the existing record.
            Map<String, String> record = records.get(testId);
            if (record == null) {
                record = new HashMap<String, String>();
                records.put(testId, record);
            }

            record.put("testId", testId);
            record.put("time", time == null ? "" : time);
            record.put("raw", JavaStandard.JsonUtil.toJson(data));

            // Respond with JSON so the exe can immediately check success and matched model data.
            Map<String, Object> result = JavaStandard.JsonUtil.newMap();
            result.put("ok", true);
            result.put("message", "received");
            result.put("testId", testId);
            result.put("time", time);
            result.put("model", modelMap.get(testId));
            JavaStandard.ServletUtil.sendJson(response, result);
        }
    }

    // Servlet for checking base data and received records currently held by the server.
    private static class Test2Servlet extends JavaStandard.HttpServlet {
        /**
         * Returns current server state as JSON.
         *
         * Response contains:
         * - models: data loaded from test.JSON.
         * - records: data received through /test.
         *
         * @param request servlet request.
         * @param response servlet response used to send JSON state.
         * @throws IOException when the response cannot be written.
         */
        @Override
        protected void doGet(JavaStandard.HttpServletRequest request,
                             JavaStandard.HttpServletResponse response) throws IOException {
            Map<String, Object> result = JavaStandard.JsonUtil.newMap();
            result.put("ok", true);
            result.put("models", modelMap);
            result.put("records", records);
            JavaStandard.ServletUtil.sendJson(response, result);
        }

        /**
         * Allows POST /test2 to behave the same as GET /test2.
         *
         * This makes simple exe clients easier to write when they always use POST.
         *
         * @param request servlet request.
         * @param response servlet response.
         * @throws IOException when the response cannot be written.
         */
        @Override
        protected void doPost(JavaStandard.HttpServletRequest request,
                              JavaStandard.HttpServletResponse response) throws IOException {
            doGet(request, response);
        }
    }
}
