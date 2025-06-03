package com.mycompany.myapp.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class NLQService {

    private static final Logger log = LoggerFactory.getLogger(NLQService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${metabase.sql.api.url:http://localhost:3000/api/dataset}")
    private String metabaseSqlApiUrl;

    @Value("${metabase.session.token}")
    private String metabaseSessionToken;

    @Value("${metabase.database.id:4}")
    private int numPredict;

    @Value("${llm.url:http://localhost:11434/api/generate}")
    private String llmUrl;

    @Value("${llm.model:codellama:7b}")
    private String llmModel;

    @Value("${llm.fallback.model:tinyllama}")
    private String llmFallbackModel;

    @Value("${application.nlq.query.max-sample-rows:3}")
    private int maxSampleRows;

    @Value("${application.nlq.query.default-limit:100}")
    private int defaultQueryLimit;

    @Value("${application.nlq.llm.temperature:0.1}")
    private double llmTemperature;

    public Map<String, Object> processNaturalLanguage(String input) {
        if (input == null || input.trim().isEmpty()) {
            return createErrorResponse("Input query cannot be empty");
        }

        try {
            log.info("Processing natural language query: {}", input);

            List<String> tableNames = getPublicTableNames();
            if (tableNames.isEmpty()) {
                return createErrorResponse("No tables found in database");
            }

            String sql = callLLMForSQL(input, tableNames);
            if (sql == null || sql.isBlank()) {
                log.warn("Falling back to default SQL query due to LLM failure");
                sql = generateDefaultSQL(input, tableNames);
            }

            log.info("Generated SQL: {}", sql);

            if (!isValidSQLQuery(sql)) {
                log.warn("Generated SQL failed validation: {}", sql);
                return createErrorResponse("Generated SQL query failed validation");
            }

            Map<String, Object> queryResult = executeMetabaseQuery(sql);

            Map<String, Object> result = new HashMap<>();
            result.put("sql", sql);
            result.put("availableTables", tableNames);
            result.put("data", queryResult);
            result.put("status", "success");
            result.put("timestamp", System.currentTimeMillis());

            return result;
        } catch (Exception e) {
            log.error("Error in processNaturalLanguage: {}", e.getMessage(), e);
            return createErrorResponse("Failed to process query: " + e.getMessage());
        }
    }

    private String generateDefaultSQL(String input, List<String> tableNames) {
        if (tableNames.isEmpty()) {
            log.warn("No tables available for default SQL");
            return "SELECT 1 as result LIMIT 1";
        }
        String lowerInput = input.toLowerCase();
        String selectedTable = tableNames.get(0); // Default to first table
        for (String table : tableNames) {
            if (lowerInput.contains(table.toLowerCase())) {
                selectedTable = table;
                break;
            }
        }
        // Extract limit from input (e.g., "first five" -> LIMIT 5)
        int limit = defaultQueryLimit;
        Pattern limitPattern = Pattern.compile("first\\s+(\\d{1,3})", Pattern.CASE_INSENSITIVE);
        Matcher limitMatcher = limitPattern.matcher(lowerInput);
        if (limitMatcher.find()) {
            try {
                limit = Integer.parseInt(limitMatcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("Invalid limit in input: {}", input);
            }
        }
        String sql = String.format("SELECT * FROM %s LIMIT %d", escapeSqlIdentifier(selectedTable), limit);
        log.debug("Generated default SQL: {}", sql);
        return sql;
    }

    private Map<String, Object> executeMetabaseQuery(String sql) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Metabase-Session", metabaseSessionToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> queryPayload = createQueryPayload(sql);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(queryPayload, headers);

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                metabaseSqlApiUrl,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("error")) {
                throw new RuntimeException("Metabase query error: " + body.get("error"));
            }

            return body;
        } catch (Exception e) {
            log.error("Error executing query in Metabase: {}", e.getMessage(), e);
            return createErrorResponse("Failed to execute query in Metabase: " + e.getMessage());
        }
    }

    @Cacheable("tableNames")
    public List<String> getPublicTableNames() {
        String sql =
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

        try {
            List<String> tables = jdbcTemplate.queryForList(sql, String.class);
            log.info("Available tables: {}", tables);
            return tables;
        } catch (DataAccessException e) {
            log.error("Error fetching table names: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Cacheable("tableSchemas")
    public Map<String, Map<String, String>> getDetailedTableSchemas(List<String> tableNames) {
        Map<String, Map<String, String>> schemas = new HashMap<>();

        String columnQuery =
            """
            SELECT column_name, data_type, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position
            """;

        for (String tableName : tableNames) {
            try {
                List<Map<String, Object>> columns = jdbcTemplate.queryForList(columnQuery, tableName);
                Map<String, String> columnInfo = new HashMap<>();

                for (Map<String, Object> column : columns) {
                    String columnName = (String) column.get("column_name");
                    String dataType = (String) column.get("data_type");
                    String nullable = (String) column.get("is_nullable");
                    String defaultValue = (String) column.get("column_default");

                    StringBuilder columnDesc = new StringBuilder(dataType);
                    if ("NO".equals(nullable)) {
                        columnDesc.append(" NOT NULL");
                    }
                    if (defaultValue != null) {
                        columnDesc.append(" DEFAULT ").append(defaultValue);
                    }

                    columnInfo.put(columnName, columnDesc.toString());
                }

                schemas.put(tableName, columnInfo);
            } catch (DataAccessException e) {
                log.error("Error fetching columns for table {}: {}", tableName, e.getMessage());
                schemas.put(tableName, Collections.emptyMap());
            }
        }

        return schemas;
    }

    @Cacheable("sampleData")
    public Map<String, List<Map<String, Object>>> getSampleData(List<String> tableNames) {
        Map<String, List<Map<String, Object>>> samples = new HashMap<>();

        for (String tableName : tableNames) {
            try {
                String sampleQuery = String.format("SELECT * FROM %s LIMIT %d", escapeSqlIdentifier(tableName), maxSampleRows);

                List<Map<String, Object>> sampleRows = jdbcTemplate.queryForList(sampleQuery);
                samples.put(tableName, sampleRows);
            } catch (DataAccessException e) {
                log.error("Error fetching sample data for {}: {}", tableName, e.getMessage());
                samples.put(tableName, Collections.emptyList());
            }
        }

        return samples;
    }

    public String buildLLMPrompt(String userQuestion, List<String> tableNames) {
        StringBuilder prompt = new StringBuilder();

        Map<String, Map<String, String>> detailedSchemas = getDetailedTableSchemas(tableNames);
        Map<String, List<Map<String, Object>>> sampleData = getSampleData(tableNames);

        prompt.append("You are a PostgreSQL expert. Convert the following natural language question to a valid PostgreSQL query. ");
        prompt.append("Return ONLY the SQL query, no explanations, no markdown, no code blocks, no extra text. ");
        prompt.append("Use the exact table and column names provided below. Ensure the query is safe and matches the user's intent.\n\n");
        prompt.append("Instructions:\n");
        prompt.append(
            "- For queries requesting a specific number of rows (e.g., 'first five employees'), use LIMIT with the exact number (e.g., LIMIT 5).\n"
        );
        prompt.append("- For filters (e.g., 'employees where age > 30'), include a WHERE clause (e.g., WHERE age > 30).\n");
        prompt.append(
            "- For sorting (e.g., 'top 5 employees by salary'), use ORDER BY with DESC/ASC and LIMIT (e.g., ORDER BY salary DESC LIMIT 5).\n"
        );
        prompt.append("- Use table and column names exactly as listed in the schema.\n\n");

        appendDatabaseSchema(prompt, tableNames, detailedSchemas, sampleData);
        appendQueryRules(prompt);

        prompt.append("Question: ").append(userQuestion).append("\n");
        prompt.append("SQL:");

        return prompt.toString();
    }

    private void appendDatabaseSchema(
        StringBuilder prompt,
        List<String> tableNames,
        Map<String, Map<String, String>> schemas,
        Map<String, List<Map<String, Object>>> samples
    ) {
        prompt.append("Database schema:\n");

        for (String tableName : tableNames) {
            prompt.append("\nTable: ").append(tableName).append("\n");
            prompt.append("Columns:\n");

            Map<String, String> columns = schemas.get(tableName);
            if (columns != null && !columns.isEmpty()) {
                columns.forEach((columnName, columnType) ->
                    prompt.append("  - ").append(columnName).append(" (").append(columnType).append(")\n")
                );
            }

            List<Map<String, Object>> tableSamples = samples.get(tableName);
            if (tableSamples != null && !tableSamples.isEmpty()) {
                prompt.append("Sample data: ");
                Map<String, Object> firstRow = tableSamples.get(0);
                List<String> sampleValues = new ArrayList<>();
                firstRow.forEach((key, value) -> sampleValues.add(key + "=" + (value != null ? value : "null")));
                prompt.append(String.join(", ", sampleValues.subList(0, Math.min(3, sampleValues.size()))));
                prompt.append("\n");
            }
        }
    }

    private void appendQueryRules(StringBuilder prompt) {
        prompt
            .append("\nRules:\n")
            .append("- Return only the SQL query\n")
            .append("- No markdown, no code blocks, no explanations\n")
            .append("- Use exact table and column names from the schema\n")
            .append("- Include LIMIT for specific row counts (e.g., 'first 5' -> LIMIT 5), otherwise use LIMIT ")
            .append(defaultQueryLimit)
            .append("\n")
            .append("- Use WHERE for filters (e.g., 'age > 30' -> WHERE age > 30)\n")
            .append("- Use ORDER BY for sorting (e.g., 'top 5 by salary' -> ORDER BY salary DESC LIMIT 5)\n")
            .append("- No semicolon at end\n")
            .append("- Ensure the query matches the user's intent (e.g., use 'employees' table for queries about employees)\n\n");
    }

    private String cleanupSQLResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            log.warn("LLM returned null or empty response");
            return null;
        }

        log.trace("Raw LLM response: {}", response);

        // Try to extract SQL from markdown code block
        Pattern markdownPattern = Pattern.compile("```(?:sql)?\\s*([\\s\\S]*?)\\s*```", Pattern.MULTILINE);
        Matcher markdownMatcher = markdownPattern.matcher(response);
        if (markdownMatcher.find()) {
            String sql = markdownMatcher.group(1).trim();
            log.trace("Extracted SQL from markdown: {}", sql);
            if (isValidSQLStart(sql)) {
                return formatSQL(sql);
            }
        }

        // Fallback: Try to find any SQL-like text
        Pattern sqlPattern = Pattern.compile("(?i)(SELECT\\s+.*?)(?:$|\\n{2,})", Pattern.DOTALL);
        Matcher sqlMatcher = sqlPattern.matcher(response);
        if (sqlMatcher.find()) {
            String sql = sqlMatcher.group(1).trim();
            log.trace("Extracted SQL from regex: {}", sql);
            if (isValidSQLStart(sql)) {
                return formatSQL(sql);
            }
        }

        // Remove markdown and try line-by-line
        response = response.replaceAll("```(?:sql)?|```|`", "").trim();
        log.trace("After markdown removal: {}", response);

        String[] lines = response.split("\n");
        StringBuilder sqlBuilder = new StringBuilder();
        boolean foundSQL = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (shouldSkipLine(line)) {
                log.trace("Skipping line: {}", line);
                continue;
            }

            if (isStartOfSQLQuery(line) || foundSQL) {
                foundSQL = true;
                sqlBuilder.append(line).append(" ");
                log.trace("Adding SQL line: {}", line);
            }
        }

        String sql = sqlBuilder.toString().trim();
        if (!sql.isEmpty() && isValidSQLStart(sql)) {
            log.trace("Extracted SQL from lines: {}", sql);
            return formatSQL(sql);
        }

        log.warn("No valid SQL found in response: {}", response);
        return null;
    }

    private boolean shouldSkipLine(String line) {
        String lowerLine = line.toLowerCase();
        return (
            lowerLine.startsWith("here's") ||
            lowerLine.startsWith("sql:") ||
            lowerLine.startsWith("query:") ||
            lowerLine.startsWith("answer:") ||
            lowerLine.contains("convert") ||
            lowerLine.contains("question") ||
            (lowerLine.length() > 50 && !lowerLine.contains("select"))
        );
    }

    private boolean isStartOfSQLQuery(String line) {
        String lowerLine = line.toLowerCase().trim();
        return lowerLine.startsWith("select") || lowerLine.startsWith("with");
    }

    private boolean isValidSQLStart(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        String lowerSQL = sql.toLowerCase().trim();
        return lowerSQL.startsWith("select") || lowerSQL.startsWith("with");
    }

    private String formatSQL(String sql) {
        sql = sql.replaceAll("\\s+", " ").trim();
        sql = sql.replaceAll(";$", "");
        // Only append default LIMIT if no LIMIT is specified
        if (!sql.toLowerCase().matches(".*\\blimit\\b\\s*\\d+.*")) {
            sql += " LIMIT " + defaultQueryLimit;
        }
        return sql;
    }

    private boolean isModelAvailable(String model) {
        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                llmUrl.replace("/api/generate", "/api/tags"),
                HttpMethod.GET,
                new HttpEntity<>(createJsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            List<Map<String, String>> models = (List<Map<String, String>>) response.getBody().get("models");
            if (models != null) {
                for (Map<String, String> m : models) {
                    if (model.equals(m.get("name"))) {
                        log.debug("Model {} is available", model);
                        return true;
                    }
                }
            }
            log.warn("Model {} not found in available models", model);
            return false;
        } catch (Exception e) {
            log.error("Failed to check model availability: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkLLMResourceAvailability(String model) {
        if (!isModelAvailable(model)) {
            log.warn("Model {} is not available in LLM service", model);
            return false;
        }

        RestTemplate restTemplate = new RestTemplate();
        try {
            Map<String, Object> testBody = new HashMap<>();
            testBody.put("model", model);
            testBody.put("prompt", "SELECT 1");
            testBody.put("stream", false);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(testBody, createJsonHeaders());

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                llmUrl,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            boolean isHealthy = response.getStatusCode().is2xxSuccessful();
            log.debug("LLM test request for model {}: {}", model, isHealthy ? "Successful" : "Failed");
            return isHealthy;
        } catch (Exception e) {
            log.warn("LLM service test request for model {} failed: {}", model, e.getMessage());
            return false;
        }
    }

    private String callLLMForSQL(String question, List<String> tableNames) {
        if (tableNames.isEmpty()) {
            log.error("No tables available in the database");
            return generateDefaultSQL(question, tableNames);
        }

        log.debug("Attempting LLM call with primary model: {}", llmModel);

        if (checkLLMResourceAvailability(llmModel)) {
            try {
                String sql = callLLMWithModel(question, tableNames, llmModel, 50);
                if (sql != null) {
                    return sql;
                }
            } catch (Exception e) {
                log.error("Primary model {} failed: {}", llmModel, e.getMessage());
                if (e.getMessage().contains("more system memory")) {
                    log.warn("Memory error detected for primary model");
                }
            }
        } else {
            log.warn("LLM service unavailable for primary model {}", llmModel);
        }

        log.debug("Attempting LLM call with fallback model: {}", llmFallbackModel);
        if (checkLLMResourceAvailability(llmFallbackModel)) {
            try {
                String sql = callLLMWithModel(question, tableNames, llmFallbackModel, 25);
                if (sql != null) {
                    return sql;
                }
            } catch (Exception e) {
                log.error("Fallback model {} failed: {}", llmFallbackModel, e.getMessage());
                if (e.getMessage().contains("more system memory")) {
                    log.warn("Memory error detected for fallback model");
                }
            }
        } else {
            log.warn("LLM service unavailable for fallback model {}", llmFallbackModel);
        }

        log.error("Both primary and fallback models failed, using default SQL");
        return generateDefaultSQL(question, tableNames);
    }

    private String callLLMWithModel(String question, List<String> tableNames, String model, int numPredict) {
        RestTemplate restTemplate = new RestTemplate();
        String prompt = buildLLMPrompt(question, tableNames);

        log.trace("LLM Prompt for model {}: {}", model, prompt);

        Map<String, Object> requestBody = createLLMRequestBody(prompt, model, numPredict);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createJsonHeaders());

        try {
            log.debug("Calling LLM at URL: {} with model: {}", llmUrl, model);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                llmUrl,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            log.debug("LLM response status: {}", response.getStatusCode());
            log.trace("LLM response body: {}", response.getBody());

            return extractSQLFromLLMResponse(response.getBody());
        } catch (Exception e) {
            log.error("Error calling LLM with model {}: {}", model, e.getMessage());
            throw e;
        }
    }

    private Map<String, Object> createLLMRequestBody(String prompt, String model, int numPredict) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", llmTemperature);
        options.put("num_predict", numPredict);
        options.put("top_p", 0.9);
        options.put("stop", Arrays.asList("\n\n", "Question:", "question:", "QUESTION:"));
        requestBody.put("options", options);

        return requestBody;
    }

    private String extractSQLFromLLMResponse(Map<String, Object> responseBody) {
        if (responseBody == null) {
            log.error("LLM response body is null");
            throw new RuntimeException("Invalid response from LLM: response body is null");
        }

        if (!responseBody.containsKey("response")) {
            log.error("LLM response missing 'response' key. Body: {}", responseBody);
            throw new RuntimeException("Invalid response from LLM: missing 'response' field");
        }

        String rawLLMResponse = (String) responseBody.get("response");
        log.trace("Raw LLM response text: {}", rawLLMResponse);

        return cleanupSQLResponse(rawLLMResponse);
    }

    private Map<String, Object> createQueryPayload(String sql) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "native");
        payload.put("native", Map.of("query", sql));
        payload.put("database", numPredict);
        return payload;
    }

    private HttpHeaders createJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("error", message);
        errorResult.put("status", "error");
        errorResult.put("timestamp", System.currentTimeMillis());
        return errorResult;
    }

    private String escapeSqlIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private boolean isValidSQLQuery(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }

        String lowerSQL = sql.toLowerCase().trim();

        // Allow SELECT queries with WHERE, ORDER BY, LIMIT
        String[] dangerousKeywords = { "drop", "delete", "truncate", "alter", "create", "insert", "update" };
        for (String keyword : dangerousKeywords) {
            if (lowerSQL.contains(keyword)) {
                log.warn("Potentially dangerous SQL detected: {}", sql);
                return false;
            }
        }

        return lowerSQL.startsWith("select") || lowerSQL.startsWith("with");
    }

    public Map<String, Object> getDatabaseInfo() {
        try {
            List<String> tables = getPublicTableNames();
            Map<String, Map<String, String>> schemas = getDetailedTableSchemas(tables);

            Map<String, Object> info = new HashMap<>();
            info.put("tables", tables);
            info.put("detailedSchemas", schemas);
            info.put("metabaseToken", metabaseSessionToken.substring(0, 8) + "...");
            info.put("databaseId", numPredict);
            info.put("status", "success");

            return info;
        } catch (Exception e) {
            log.error("Failed to get database info: {}", e.getMessage(), e);
            return createErrorResponse("Failed to get database info: " + e.getMessage());
        }
    }

    public Map<String, Object> testMetabaseConnection() {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Metabase-Session", metabaseSessionToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            String testSQL = "SELECT 1 as test_column";
            Map<String, Object> queryPayload = createQueryPayload(testSQL);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(queryPayload, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                metabaseSqlApiUrl,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            Map<String, Object> result = new HashMap<>();
            result.put("status", "connected");
            result.put("response", response.getBody());
            return result;
        } catch (Exception e) {
            log.error("Metabase connection test failed: {}", e.getMessage(), e);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "failed");
            result.put("error", e.getMessage());
            return result;
        }
    }

    public Map<String, Object> debugLLMResponse(String question) {
        try {
            List<String> tableNames = getPublicTableNames();
            String prompt = buildLLMPrompt(question, tableNames);

            Map<String, Object> result = new HashMap<>();
            result.put("prompt", prompt);
            result.put("tables", tableNames);

            RestTemplate restTemplate = new RestTemplate();
            Map<String, Object> requestBody = createLLMRequestBody(prompt, llmModel, 50);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createJsonHeaders());

            try {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    llmUrl,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
                );

                result.put("llmRawResponsePrimary", response.getBody());

                if (response.getBody() != null && response.getBody().containsKey("response")) {
                    String rawResponse = (String) response.getBody().get("response");
                    result.put("rawTextPrimary", rawResponse);
                    String cleanedSQL = cleanupSQLResponse(rawResponse);
                    result.put("cleanedSQLPrimary", cleanedSQL);
                    result.put("isValidPrimary", cleanedSQL != null && isValidSQLQuery(cleanedSQL));
                }
            } catch (Exception e) {
                result.put("errorPrimary", e.getMessage());
            }

            requestBody = createLLMRequestBody(prompt, llmFallbackModel, 25);
            entity = new HttpEntity<>(requestBody, createJsonHeaders());

            try {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    llmUrl,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
                );

                result.put("llmRawResponseFallback", response.getBody());

                if (response.getBody() != null && response.getBody().containsKey("response")) {
                    String rawResponse = (String) response.getBody().get("response");
                    result.put("rawTextFallback", rawResponse);
                    String cleanedSQL = cleanupSQLResponse(rawResponse);
                    result.put("cleanedSQLFallback", cleanedSQL);
                    result.put("isValidFallback", cleanedSQL != null && isValidSQLQuery(cleanedSQL));
                }
            } catch (Exception e) {
                result.put("errorFallback", e.getMessage());
            }

            return result;
        } catch (Exception e) {
            log.error("Debug LLM response failed: {}", e.getMessage(), e);
            return createErrorResponse("Debug failed: " + e.getMessage());
        }
    }
}
