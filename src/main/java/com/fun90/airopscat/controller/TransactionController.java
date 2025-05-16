package com.fun90.airopscat.controller;

import com.fun90.airopscat.model.dto.TransactionDto;
import com.fun90.airopscat.model.entity.Transaction;
import com.fun90.airopscat.model.enums.PaymentMethod;
import com.fun90.airopscat.model.enums.TransactionType;
import com.fun90.airopscat.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/admin/transactions")
public class TransactionController {
    
    private final TransactionService transactionService;
    
    @Autowired
    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getTransactionPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) String businessTable,
            @RequestParam(required = false) Long businessId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate
    ) {
        Page<Transaction> transactionPage = transactionService.getTransactionPage(
                page, size, search, type, businessTable, businessId, startDate, endDate);
        
        // Convert to DTOs
        List<TransactionDto> transactionDtos = transactionPage.getContent().stream()
                .map(transaction -> transactionService.convertToDto(transaction))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("records", transactionDtos);
        response.put("total", transactionPage.getTotalElements());
        response.put("pages", transactionPage.getTotalPages());
        response.put("current", page);
        response.put("size", size);
        
        // Add statistics
        response.put("stats", transactionService.getTransactionStats());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionDto> getTransactionById(@PathVariable Long id) {
        Transaction transaction = transactionService.getTransactionById(id);
        if (transaction != null) {
            TransactionDto dto = transactionService.convertToDto(transaction);
            return ResponseEntity.ok(dto);
        }
        return ResponseEntity.notFound().build();
    }
    
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, String>>> getTransactionTypes() {
        List<Map<String, String>> types = Stream.of(TransactionType.values())
                .map(type -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("value", String.valueOf(type.getValue()));
                    map.put("label", type.getDescription());
                    return map;
                })
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(types);
    }

    @GetMapping("/paymentMethods")
    public ResponseEntity<List<Map<String, String>>> getPaymentMethods() {
        List<Map<String, String>> types = Stream.of(PaymentMethod.values())
                .map(type -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("value", type.getValue());
                    map.put("label", type.getDescription());
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(types);
    }
    
    @GetMapping("/business-tables")
    public ResponseEntity<List<Map<String, String>>> getBusinessTables() {
        List<Map<String, String>> tables = List.of(
                createMapEntry("account", "账户"),
                createMapEntry("domain", "域名"),
                createMapEntry("server", "服务器")
        );
        
        return ResponseEntity.ok(tables);
    }
    
    private Map<String, String> createMapEntry(String value, String label) {
        Map<String, String> map = new HashMap<>();
        map.put("value", value);
        map.put("label", label);
        return map;
    }
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getTransactionStats() {
        return ResponseEntity.ok(transactionService.getTransactionStats());
    }
    
    @GetMapping("/monthly-stats")
    public ResponseEntity<List<Map<String, Object>>> getMonthlyStats(
            @RequestParam(defaultValue = "6") int months
    ) {
        return ResponseEntity.ok(transactionService.getMonthlyStats(months));
    }
    
    @GetMapping("/business/{businessTable}/{businessId}")
    public ResponseEntity<List<TransactionDto>> getTransactionsByBusiness(
            @PathVariable String businessTable,
            @PathVariable Long businessId
    ) {
        List<Transaction> transactions = transactionService.getByBusinessTableAndId(businessTable, businessId);
        List<TransactionDto> dtos = transactions.stream()
                .map(transaction -> transactionService.convertToDto(transaction))
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    public ResponseEntity<Transaction> createTransaction(@RequestBody Transaction transaction) {
        Transaction savedTransaction = transactionService.saveTransaction(transaction);
        return ResponseEntity.ok(savedTransaction);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Transaction> updateTransaction(@PathVariable Long id, @RequestBody Transaction transaction) {
        Transaction existingTransaction = transactionService.getTransactionById(id);
        if (existingTransaction == null) {
            return ResponseEntity.notFound().build();
        }

        transaction.setId(id);
        Transaction updatedTransaction = transactionService.updateTransaction(transaction);
        return ResponseEntity.ok(updatedTransaction);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable Long id) {
        Transaction existingTransaction = transactionService.getTransactionById(id);
        if (existingTransaction == null) {
            return ResponseEntity.notFound().build();
        }

        transactionService.deleteTransaction(id);
        return ResponseEntity.ok().build();
    }
}