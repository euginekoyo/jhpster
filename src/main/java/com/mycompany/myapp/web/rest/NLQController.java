package com.mycompany.myapp.web.rest;

import com.mycompany.myapp.service.NLQService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/nlq")
@CrossOrigin(origins = { "http://localhost:3000", "http://localhost:3001", "http://127.0.0.1:3000" })
public class NLQController {

    @Autowired
    private NLQService nlqService;

    /**
     * Main endpoint for natural language queries
     */
    @PostMapping
    public ResponseEntity<?> handleQuery(@RequestBody Map<String, String> body) {
        try {
            String userInput = body.get("query");

            if (userInput == null || userInput.trim().isEmpty()) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "Query parameter is required and cannot be empty");
                return ResponseEntity.badRequest().body(error);
            }

            Map<String, Object> result = nlqService.processNaturalLanguage(userInput.trim());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to process query: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get database schema information
     */
    @GetMapping("/database-info")
    public ResponseEntity<?> getDatabaseInfo() {
        try {
            Map<String, Object> info = nlqService.getDatabaseInfo();
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to fetch database info: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get just the table names
     */
    @GetMapping("/tables")
    public ResponseEntity<?> getTableNames() {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("tables", nlqService.getPublicTableNames());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to fetch table names: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Test Metabase connectivity
     */
    @GetMapping("/test-metabase")
    public ResponseEntity<?> testMetabase() {
        try {
            Map<String, Object> result = nlqService.testMetabaseConnection();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, String> error = new HashMap<>();
            error.put("error", "Failed to test Metabase connection: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get example queries
     */
    @GetMapping("/examples")
    public ResponseEntity<?> getExampleQueries() {
        Map<String, Object> examples = new HashMap<>();

        examples.put(
            "basic_queries",
            new String[] { "List all regions", "Show all employees", "What are the job titles?", "List all countries", "Show departments" }
        );

        examples.put(
            "complex_queries",
            new String[] {
                "How many employees are in each department?",
                "Which regions have the most countries?",
                "Show employees with their job titles",
                "List countries by region",
                "What is the average salary by department?",
            }
        );

        return ResponseEntity.ok(examples);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        Map<String, Object> health = new HashMap<>();

        try {
            // Test database connectivity
            var tables = nlqService.getPublicTableNames();
            health.put("database", "connected");
            health.put("tableCount", tables.size());

            // Test Metabase connectivity
            var metabaseTest = nlqService.testMetabaseConnection();
            health.put("metabase", metabaseTest.get("status"));

            health.put("status", "healthy");
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            health.put("database", "error: " + e.getMessage());
            health.put("status", "unhealthy");
            return ResponseEntity.internalServerError().body(health);
        }
    }
}
