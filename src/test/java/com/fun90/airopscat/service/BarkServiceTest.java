//package com.fun90.airopscat.service;
//
//import com.fun90.airopscat.model.dto.BarkNotificationDto;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpMethod;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.springframework.test.util.ReflectionTestUtils;
//import org.springframework.web.client.RestTemplate;
//
//import java.net.URI;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * BarkService测试类
// * 支持GET和POST两种请求方式
// * 参考Bark官方文档: https://github.com/Finb/Bark
// */
//@ExtendWith(MockitoExtension.class)
//class BarkServiceTest {
//
//    @Mock
//    private RestTemplate restTemplate;
//
//    @InjectMocks
//    private BarkService barkService;
//
//    private static final String TEST_BARK_URL = "https://push.fun90.com";
//    private static final String TEST_DEVICE_KEY = "8TdAZNri6RV5kWu8dskjQb";
//
//    @BeforeEach
//    void setUp() {
//        ReflectionTestUtils.setField(barkService, "barkUrl", TEST_BARK_URL);
//        ReflectionTestUtils.setField(barkService, "deviceKey", TEST_DEVICE_KEY);
//        ReflectionTestUtils.setField(barkService, "restTemplate", restTemplate);
//    }
//
//    @Test
//    void testSendNotification_GET_Success() {
//        // 准备测试数据
//        String title = "测试标题";
//        String body = "测试内容";
//
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":200,\"message\":\"success\"}", HttpStatus.OK));
//
//        // 执行测试
//        boolean result = barkService.sendNotification(title, body);
//
//        // 验证结果
//        assertTrue(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendNotification_GET_Failure() {
//        // 准备测试数据
//        String title = "测试标题";
//        String body = "测试内容";
//
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":400,\"message\":\"error\"}", HttpStatus.BAD_REQUEST));
//
//        // 执行测试
//        boolean result = barkService.sendNotification(title, body);
//
//        // 验证结果
//        assertFalse(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendNotification_GET_Exception() {
//        // 准备测试数据
//        String title = "测试标题";
//        String body = "测试内容";
//
//        // Mock RestTemplate抛出异常
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenThrow(new RuntimeException("网络错误"));
//
//        // 执行测试
//        boolean result = barkService.sendNotification(title, body);
//
//        // 验证结果
//        assertFalse(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendNotification_POST_Success() {
//        // 准备测试数据
//        String title = "测试标题";
//        String body = "测试内容";
//
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":200,\"message\":\"success\"}", HttpStatus.OK));
//
//        // 执行测试
//        boolean result = barkService.sendNotification(title, body);
//
//        // 验证结果
//        assertTrue(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendNotification_POST_Failure() {
//        // 准备测试数据
//        String title = "测试标题";
//        String body = "测试内容";
//
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":400,\"message\":\"error\"}", HttpStatus.BAD_REQUEST));
//
//        // 执行测试
//        boolean result = barkService.sendNotification(title, body);
//
//        // 验证结果
//        assertFalse(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendNotification_POST_Exception() {
//        // 准备测试数据
//        String title = "测试标题";
//        String body = "测试内容";
//
//        // Mock RestTemplate抛出异常
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenThrow(new RuntimeException("网络错误"));
//
//        // 执行测试
//        boolean result = barkService.sendNotification(title, body);
//
//        // 验证结果
//        assertFalse(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendNotificationGet_GET_Success() {
//        // 准备测试数据
//        BarkNotificationDto notification = BarkNotificationDto.builder()
//                .title("GET测试")
//                .body("GET测试内容")
//                .build();
//
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":200,\"message\":\"success\"}", HttpStatus.OK));
//
//        // 执行测试
//        boolean result = barkService.sendNotificationGet(notification);
//
//        // 验证结果
//        assertTrue(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendNotificationGet_GET_Failure() {
//        // 准备测试数据
//        BarkNotificationDto notification = BarkNotificationDto.builder()
//                .title("GET测试")
//                .body("GET测试内容")
//                .build();
//
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":400,\"message\":\"error\"}", HttpStatus.BAD_REQUEST));
//
//        // 执行测试
//        boolean result = barkService.sendNotificationGet(notification);
//
//        // 验证结果
//        assertFalse(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.GET),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendCustomNotification_POST() {
//        // 准备测试数据
//        BarkNotificationDto notification = BarkNotificationDto.builder()
//                .title("自定义通知")
//                .body("自定义内容")
//                .icon("https://example.com/icon.png")
//                .sound("alarm")
//                .group("测试组")
//                .level("active")
//                .build();
//
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":200,\"message\":\"success\"}", HttpStatus.OK));
//
//        // 执行测试
//        boolean result = barkService.sendNotification(notification);
//
//        // 验证结果
//        assertTrue(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendWarningNotification() {
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":200,\"message\":\"success\"}", HttpStatus.OK));
//
//        // 执行测试
//        boolean result = barkService.sendWarningNotification("警告", "这是一个警告");
//
//        // 验证结果
//        assertTrue(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendErrorNotification() {
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":200,\"message\":\"success\"}", HttpStatus.OK));
//
//        // 执行测试
//        boolean result = barkService.sendErrorNotification("错误", "这是一个错误");
//
//        // 验证结果
//        assertTrue(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testSendInfoNotification() {
//        // Mock RestTemplate响应
//        when(restTemplate.exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        )).thenReturn(new ResponseEntity<>("{\"code\":200,\"message\":\"success\"}", HttpStatus.OK));
//
//        // 执行测试
//        boolean result = barkService.sendInfoNotification("信息", "这是一条信息");
//
//        // 验证结果
//        assertTrue(result);
//        verify(restTemplate, times(1)).exchange(
//                any(URI.class),
//                eq(HttpMethod.POST),
//                any(HttpEntity.class),
//                eq(String.class)
//        );
//    }
//
//    @Test
//    void testIsBarkConfigured_True() {
//        // 执行测试
//        boolean result = barkService.isBarkConfigured();
//
//        // 验证结果
//        assertTrue(result);
//    }
//
//    @Test
//    void testIsBarkConfigured_False_EmptyUrl() {
//        // 设置空的Bark URL
//        ReflectionTestUtils.setField(barkService, "barkUrl", "");
//
//        // 执行测试
//        boolean result = barkService.isBarkConfigured();
//
//        // 验证结果
//        assertFalse(result);
//    }
//
//    @Test
//    void testIsBarkConfigured_False_EmptyDeviceKey() {
//        // 设置空的设备密钥
//        ReflectionTestUtils.setField(barkService, "deviceKey", "");
//
//        // 执行测试
//        boolean result = barkService.isBarkConfigured();
//
//        // 验证结果
//        assertFalse(result);
//    }
//
//    @Test
//    void testIsBarkConfigured_Null() {
//        // 设置null的配置
//        ReflectionTestUtils.setField(barkService, "barkUrl", null);
//        ReflectionTestUtils.setField(barkService, "deviceKey", null);
//
//        // 执行测试
//        boolean result = barkService.isBarkConfigured();
//
//        // 验证结果
//        assertFalse(result);
//    }
//
//    @Test
//    void testGetBarkUrl() {
//        // 执行测试
//        String result = barkService.getBarkUrl();
//
//        // 验证结果
//        assertEquals(TEST_BARK_URL, result);
//    }
//
//    @Test
//    void testGetDeviceKey() {
//        // 执行测试
//        String result = barkService.getDeviceKey();
//
//        // 验证结果
//        assertEquals(TEST_DEVICE_KEY, result);
//    }
//
//    @Test
//    void testSendNotification_NotConfigured() {
//        // 设置空的配置
//        ReflectionTestUtils.setField(barkService, "barkUrl", "");
//        ReflectionTestUtils.setField(barkService, "deviceKey", "");
//
//        // 准备测试数据
//        BarkNotificationDto notification = BarkNotificationDto.builder()
//                .title("测试")
//                .body("内容")
//                .build();
//
//        // 执行测试
//        boolean result = barkService.sendNotification(notification);
//
//        // 验证结果
//        assertFalse(result);
//        verify(restTemplate, never()).exchange(
//                any(URI.class),
//                any(HttpMethod.class),
//                any(HttpEntity.class),
//                any(Class.class)
//        );
//    }
//}