package com.test;

import static org.junit.Assert.assertNotNull;

import java.math.BigInteger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit4.SpringRunner;

import com.Application;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persistence.Repo.UserRepository;
import com.persistence.model.User;
import com.service.education.IDashboardService;
import com.spring.PersistenceJPAConfig;
import com.spring.TestIntegrationConfig;
import com.validation.EmailExistsException;
import com.web.dto.education.DashboardDTO;
import com.web.util.AppUtil;

import io.restassured.RestAssured;
import io.restassured.authentication.FormAuthConfig;



@RunWith(SpringRunner.class)
@SpringBootTest(classes = { Application.class, PersistenceJPAConfig.class, TestIntegrationConfig.class }, webEnvironment = WebEnvironment.RANDOM_PORT)
public class ChangePasswordIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    IDashboardService service;
    
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${local.server.port}")
    int port;

    private FormAuthConfig formConfig;
    private String URL;

    //

    @Before
    public void init() {
        User user = userRepository.findByEmail("test@test.com");
        if (user == null) {
            user = new User();
            user.setFirstName("Test");
            user.setLastName("Test");
            user.setPassword(passwordEncoder.encode("test"));
            user.setEmail("test@test.com");
            user.setEnabled(true);
            userRepository.save(user);
        } else {
            user.setPassword(passwordEncoder.encode("test"));
            userRepository.save(user);
        }

        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
        URL = "/user/updatePassword";
        formConfig = new FormAuthConfig("/login", "username", "password");
    }
//
//    @Test
//    public void givenNotAuthenticatedUser_whenLoggingIn_thenCorrect() {
//        final RequestSpecification request = RestAssured.given().auth().form("test@test.com", "test", formConfig);
//
//        request.when().get("/console.html").then().assertThat().statusCode(200).and().body(containsString("home"));
//    }
//
//    @Test
//    public void givenNotAuthenticatedUser_whenBadPasswordLoggingIn_thenCorrect() {
//        final RequestSpecification request = RestAssured.given().auth().form("XXXXXXXX@XXXXXXXXX.com", "XXXXXXXX", formConfig).redirects().follow(false);
//
//        request.when().get("/console.html").then().statusCode(IsNot.not(200)).body(isEmptyOrNullString());
//    }
//
//    @Test
//    public void givenLoggedInUser_whenChangingPassword_thenCorrect() {
//        final RequestSpecification request = RestAssured.given().auth().form("test@test.com", "test", formConfig);
//
//        final Map<String, String> params = new HashMap<String, String>();
//        params.put("oldPassword", "test");
//        params.put("newPassword", "newTest&12");
//
//        final Response response = request.with().queryParams(params).post(URL);
//
//        assertEquals(200, response.statusCode());
//        assertTrue(response.body().asString().contains("Password updated successfully"));
//    }
//
//    @Test
//    public void givenWrongOldPassword_whenChangingPassword_thenBadRequest() {
//        final RequestSpecification request = RestAssured.given().auth().form("test@test.com", "test", formConfig);
//
//        final Map<String, String> params = new HashMap<String, String>();
//        params.put("oldPassword", "abc");
//        params.put("newPassword", "newTest&12");
//
//        final Response response = request.with().queryParams(params).post(URL);
//
//        assertEquals(400, response.statusCode());
//        assertTrue(response.body().asString().contains("Invalid Old Password"));
//    }
//
//    @Test
//    public void givenNotAuthenticatedUser_whenChangingPassword_thenRedirect() {
//        final Map<String, String> params = new HashMap<String, String>();
//        params.put("oldPassword", "abc");
//        params.put("newPassword", "xyz");
//
//        final Response response = RestAssured.with().params(params).post(URL);
//
//        assertEquals(302, response.statusCode());
//        assertFalse(response.body().asString().contains("Password updated successfully"));
//    }
    @Test
    public void getDashboardData() throws EmailExistsException {
    	AppUtil appUtil = new AppUtil();
    	DashboardDTO dto = DashboardDTO.builder().lastMonth(appUtil.getLocalDateForDBStr(appUtil.dateOfLastMonth(1))).build();
//    	IDashboardService service = new DashboardService();
    	Object obj = null;//userRepository.getDashboardData(appUtil.getLocalDateForDBStr(appUtil.dateOfLastMonth(1)));//"2019-11-01"
    	Object[] line =  (Object[]) obj;
    	dto.setAllStudent(((BigInteger)line[1]).longValue());
    	dto.setFreshStudent(((BigInteger)line[0]).longValue());
    	    	
    	ObjectMapper mapper = new ObjectMapper();
    	try {
    		String jsonString = mapper.writeValueAsString(dto);
			System.out.print(jsonString);
			assertNotNull(jsonString);
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
}
