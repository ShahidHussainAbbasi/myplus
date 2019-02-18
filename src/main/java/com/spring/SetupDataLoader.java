package com.spring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ResourceBundle;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.persistence.Repo.PrivilegeRepository;
import com.persistence.Repo.RoleRepository;
//import com.persistence.Repo.TypeRepository;
import com.persistence.Repo.UserRepository;
import com.persistence.model.Privilege;
import com.persistence.model.Role;
import com.persistence.model.User;


@Component
public class SetupDataLoader implements ApplicationListener<ContextRefreshedEvent> {

    private boolean alreadySetup = false;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PrivilegeRepository privilegeRepository;

//    @Autowired
//    private TypeRepository typeRepository;

//    @Autowired
//    private ServiceRepository serviceRepository;
//
    @Autowired
    private PasswordEncoder passwordEncoder;

    // API

    @SuppressWarnings("null")
	@Override
    @Transactional
    public void onApplicationEvent(final ContextRefreshedEvent event) {
        if (alreadySetup) {
            return;
        }

        // == create initial education role & privileges
    	ArrayList<Privilege> userPrivileges = new ArrayList<>();
    	ArrayList<Privilege> generalPrivileges = new ArrayList<>();
        ResourceBundle rb = ResourceBundle.getBundle("role_privileges_business");
    	//adding privileges
    	ArrayList<Privilege> superBusinessPrivileges = new ArrayList<>();
    	ArrayList<Privilege> adminBusinessPrivileges = new ArrayList<>();
    	ArrayList<Privilege> userBusinessPrivileges = new ArrayList<>();
    	for(String key:rb.keySet()) {
    		System.out.println(key);
    		System.out.println(rb.getString(key));
    		if(key.startsWith("general.privilege")) {
    			generalPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("admin.business.privilege")){
    			adminBusinessPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("user.business.privilege")){
    			userBusinessPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("super.business.privilege")){
    			superBusinessPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("user.privilege")){
    			userPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}
    		rb.keySet().remove(key);
    	}
    	//adding roles
    	for(String key:rb.keySet()) {
    		Role role = null;
    		if(key.startsWith("general.role")) {
    			role = createRoleIfNotFound(rb.getString(key), generalPrivileges);
    		}else if(key.startsWith("business.role.guest")) {
    			role = createRoleIfNotFound(rb.getString(key), generalPrivileges);
    		}else if(key.startsWith("business.role.user")) {
    			userBusinessPrivileges.addAll(generalPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), userBusinessPrivileges);
    		}else if(key.startsWith("business.role.admin")) {
    			adminBusinessPrivileges.addAll(generalPrivileges);
    			adminBusinessPrivileges.addAll(userBusinessPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), adminBusinessPrivileges);
    		}else if(key.startsWith("business.role.super")) {
    			superBusinessPrivileges.addAll(generalPrivileges);
    			superBusinessPrivileges.addAll(userBusinessPrivileges);
    			superBusinessPrivileges.addAll(adminBusinessPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), superBusinessPrivileges);
    		}else if(key.startsWith("user.role")) {
    			userPrivileges.addAll(generalPrivileges);
    			userPrivileges.addAll(userBusinessPrivileges);
    			userPrivileges.addAll(adminBusinessPrivileges);
    			userPrivileges.addAll(superBusinessPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), userPrivileges);
    		}
    		if(key.startsWith("general.role")) {
    			createRoleIfNotFound(rb.getString(key), generalPrivileges);
    	        createUserIfNotFound("guest@guest.com", "Guest", "Guest", "guest", new ArrayList<Role>(Arrays.asList(role)),rb.getString("guest.user.type"));
    		}else if(key.startsWith("business.role.guest")) {
    			createRoleIfNotFound(rb.getString(key), generalPrivileges);
    	        createUserIfNotFound("general@general.com", "General", "General", "test", new ArrayList<Role>(Arrays.asList(role)),rb.getString("general.user.type"));
    		}else if(key.startsWith("business.role.user")) {
    			createRoleIfNotFound(rb.getString(key), userPrivileges);
    	        createUserIfNotFound("uncer_sh@yahoo.com", "Shahid", "Hussain", "user", new ArrayList<Role>(Arrays.asList(role)),rb.getString("business.user.type"));
    		}else if(key.startsWith("business.role.admin")) {
    			createRoleIfNotFound(rb.getString(key), adminBusinessPrivileges);
    	        createUserIfNotFound("email2uncer@gmail.com", "Shahid", "Hussain", "admin", new ArrayList<Role>(Arrays.asList(role)),rb.getString("business.user.type"));
    	        createUserIfNotFound("sameerfaisal29@gmail.com", "Faisal", "Fameer", "03453176525", new ArrayList<Role>(Arrays.asList(role)),rb.getString("business.user.type"));
    		}else if(key.startsWith("business.role.super")) {
    			createRoleIfNotFound(rb.getString(key), superBusinessPrivileges);
    	        createUserIfNotFound("maxtheservice@gmail.com", "Shahid", "Hussain", "super", new ArrayList<Role>(Arrays.asList(role)),rb.getString("business.user.type"));
    		}else if(key.startsWith("user.role")) {
    			createRoleIfNotFound(rb.getString(key), userPrivileges);
    	        createUserIfNotFound("user@user.com", "Shahid", "Hussain", "user", new ArrayList<Role>(Arrays.asList(role)),rb.getString("business.user.type"));
    		}
    		
    	}
    	
    	//2. create role & privilege of business
        rb = ResourceBundle.getBundle("role_privileges_education");
    	//adding privileges
    	ArrayList<Privilege> superEducationPrivileges = new ArrayList<>();
    	ArrayList<Privilege> adminEducationPrivileges = new ArrayList<>();
    	ArrayList<Privilege> userEducationPrivileges = new ArrayList<>();
    	for(String key:rb.keySet()) {
    		if(key.startsWith("general.privilege")) {
    			generalPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("admin.education.privilege")){
    			adminEducationPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("user.education.privilege")){
    			userEducationPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("super.education.privilege")){
    			superEducationPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("user.privilege")){
    			userPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}
    		rb.keySet().remove(key);
    	}
    	//adding roles
    	for(String key:rb.keySet()) {
    		Role role = null;
    		if(key.startsWith("education.role.guest")) {
    			role = createRoleIfNotFound(rb.getString(key), generalPrivileges);
    		}else if(key.startsWith("education.role.user")) {
    			userEducationPrivileges.addAll(generalPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), userEducationPrivileges);
    		}else if(key.startsWith("education.role.admin")) {
    			adminEducationPrivileges.addAll(generalPrivileges);
    			adminEducationPrivileges.addAll(userEducationPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), adminEducationPrivileges);
    		}else if(key.startsWith("education.role.super")) {
    			superEducationPrivileges.addAll(generalPrivileges);
    			superEducationPrivileges.addAll(userEducationPrivileges);
    			superEducationPrivileges.addAll(adminEducationPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), superEducationPrivileges);
    		}else if(key.startsWith("user.role")) {
    			userPrivileges.addAll(generalPrivileges);
    			userPrivileges.addAll(userEducationPrivileges);
    			userPrivileges.addAll(adminEducationPrivileges);
    			userPrivileges.addAll(superEducationPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), userPrivileges);
    		}
    		if(key.startsWith("general.role")) {
    			createRoleIfNotFound(rb.getString(key), generalPrivileges);
    	        createUserIfNotFound("guest@edu.com", "Guest", "Guest", "guest", new ArrayList<Role>(Arrays.asList(role)),rb.getString("guest.user.type"));
    		}else if(key.startsWith("education.role.guest")) {
    			createRoleIfNotFound(rb.getString(key), generalPrivileges);
    	        createUserIfNotFound("general@edu.com", "General", "General", "test", new ArrayList<Role>(Arrays.asList(role)),rb.getString("general.user.type"));
    		}else if(key.startsWith("education.role.user")) {
    			createRoleIfNotFound(rb.getString(key), userEducationPrivileges);
    	        createUserIfNotFound("user@edu.com", "Shahid", "Hussain", "user", new ArrayList<Role>(Arrays.asList(role)),rb.getString("education.user.type"));
    		}else if(key.startsWith("education.role.admin")) {
    			createRoleIfNotFound(rb.getString(key), adminEducationPrivileges);
    	        createUserIfNotFound("admin@edu.com", "Shahid", "Hussain", "admin", new ArrayList<Role>(Arrays.asList(role)),rb.getString("education.user.type"));
    		}else if(key.startsWith("education.role.super")) {
    			createRoleIfNotFound(rb.getString(key), superEducationPrivileges);
    	        createUserIfNotFound("super@edu.com", "Shahid", "Hussain", "super", new ArrayList<Role>(Arrays.asList(role)),rb.getString("education.user.type"));
    		}else if(key.startsWith("user.role")) {
    			createRoleIfNotFound(rb.getString(key), userPrivileges);
    	        createUserIfNotFound("user@user.com", "Shahid", "Hussain", "user", new ArrayList<Role>(Arrays.asList(role)),rb.getString("education.user.type"));
    		}
    	}
 
    	//3. create role & privilege of welfare
        rb = ResourceBundle.getBundle("role_privileges_welfare");
    	//adding privileges
    	ArrayList<Privilege> userWelfarePrivileges = new ArrayList<>();
    	for(String key:rb.keySet()) {
    		if(key.startsWith("general.privilege")) {
    			generalPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("user.welfare.privilege")){
    			userWelfarePrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}else if(key.startsWith("user.privilege")){
    			userPrivileges.add(createPrivilegeIfNotFound(rb.getString(key)));
    		}
    		rb.keySet().remove(key);
    	}
    	//adding roles
    	for(String key:rb.keySet()) {
    		Role role = null;
    		if(key.startsWith("welfare.role.gues")) {
    			role = createRoleIfNotFound(rb.getString(key), generalPrivileges);
    		}else if(key.startsWith("welfare.role.user")) {
    			userWelfarePrivileges.addAll(generalPrivileges);
    			role = createRoleIfNotFound(rb.getString(key), userWelfarePrivileges);
    		}else if(key.startsWith("user.role")) {
    			userPrivileges.addAll(generalPrivileges);
    			userPrivileges.addAll(userWelfarePrivileges);
    			role = createRoleIfNotFound(rb.getString(key), userPrivileges);
    		}
    		if(key.startsWith("general.role")) {
    			createRoleIfNotFound(rb.getString(key), generalPrivileges);
    	        createUserIfNotFound("general@welfare.com", "Guest", "Guest", "test", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    		}else if(key.startsWith("welfare.role.guest")) {
    			createRoleIfNotFound(rb.getString(key), generalPrivileges);
    	        createUserIfNotFound("guest@welfare.com", "General", "General", "guest", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    	        createUserIfNotFound("mehmoodabasi7761@gmail.com", "Mehmhood", "Abbasi", "03027865238", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    	        createUserIfNotFound("tehmasabbasiabbasi@gmail.com", "Tehmas", "Abbasi", "03083241609", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    	        createUserIfNotFound("almasabbasi7749@gmail.com", "Almas", "Abbasi", "03003459577", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    	        createUserIfNotFound("minhasahmad110@gmail.com", "Minhas", "Abbasi", "03083243904", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));

    	        createUserIfNotFound("majidabbasi123@icloud.com", "Majid", "Abbasi", "966557029912", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    	        createUserIfNotFound("nazimdte@gmail.com", "Nazim", "Hussain", "03012697735", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    	        createUserIfNotFound("khanhashimabbasi@gmail.com", "Hashim", "Abbasi", "03002128561", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    		}else if(key.startsWith("welfare.role.user")) {
    			createRoleIfNotFound(rb.getString(key), userWelfarePrivileges);
    			createUserIfNotFound("younisabbasi9@gmail.com", "Younis", "Abbasi", "03026556089", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    		}else if(key.startsWith("user.role")) {
    			createRoleIfNotFound(rb.getString(key), userPrivileges);
    	        createUserIfNotFound("user@welfare.com", "Shahid", "Hussain", "user", new ArrayList<Role>(Arrays.asList(role)),rb.getString("welfare.user.type"));
    		}
    	}
    	
        // == create initial basic privileges
/*        final Privilege BUSINESS_PRIVILEGE = createPrivilegeIfNotFound(env.getRequiredProperty("user.type"));//createPrivilegeIfNotFound("BUSINESS");
        final Privilege CHANGE_PASSWORD_PRIVILEGE = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.change.password"));
        final Privilege LOGIN_PRIVILEGE = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));

        final Privilege readPrivilege = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.read"));
        final Privilege writePrivilege = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.write"));
        final Privilege updatePrivilege = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege deletePrivilege = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        //create service level privileges
        final Privilege GET_COMPANY = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege GET_VENDER = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege GET_ITEM = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege GET_ITEM_TYPE = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege GET_ITEM_UNIT = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege ADD_COMPANY = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege ADD_VENDER = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege ADD_ITEM = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege ADD_ITEM_TYPE = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege ADD_ITEM_UNIT = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege UPDATE_COMPANY = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege UPDATE_VENDER = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege UPDATE_ITEM = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege UPDATE_ITEM_TYPE = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege UPDATE_ITEM_UNIT = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege DELETE_COMPANY = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege DELETE_VENDER = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege DELETE_ITEM = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege DELETE_ITEM_TYPE = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        final Privilege DELETE_ITEM_UNIT = createPrivilegeIfNotFound(env.getRequiredProperty("user.privilege.login"));
        
//        final Privilege pharmacyPrivilegeSuperServices = createPrivilegeIfNotFound("PHARMACY",superServices);
//        final Privilege pharmacyPrivilegeAdminServices = createPrivilegeIfNotFound("PHARMACY",adminServices);
//        final Privilege pharmacyPrivilegeUserServices = createPrivilegeIfNotFound("PHARMACY",userServices);
//        final Privilege pharmacyPrivilegeSubUserServices = createPrivilegeIfNotFound("PHARMACY",subUserServices);
//        final Privilege pharmacyPrivilegeGuestServices = createPrivilegeIfNotFound("PHARMACY",guestServices);
        

        // == create initial basic privileges
//        final List<Privilege> superPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege,writePrivilege, passwordPrivilege,updatePrivilege,deletePrivilege));
//        final List<Privilege> adminPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege,writePrivilege, passwordPrivilege,updatePrivilege,deletePrivilege));
//        final List<Privilege> userPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege,writePrivilege,updatePrivilege));
//        final List<Privilege> subUserPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege, writePrivilege));
//        final List<Privilege> guestPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege));
        //create business level privileges
        final List<Privilege> readBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(BUSINESS_PRIVILEGE,GET_COMPANY, GET_VENDER,GET_ITEM, GET_ITEM_TYPE,GET_ITEM_UNIT));
        final List<Privilege> addBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(BUSINESS_PRIVILEGE,ADD_COMPANY, ADD_VENDER,ADD_ITEM,ADD_ITEM_TYPE,ADD_ITEM_UNIT));
        final List<Privilege> updateBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(CHANGE_PASSWORD_PRIVILEGE,BUSINESS_PRIVILEGE,UPDATE_COMPANY, UPDATE_VENDER,UPDATE_ITEM, UPDATE_ITEM_TYPE, UPDATE_ITEM_UNIT));
        final List<Privilege> deleteBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(BUSINESS_PRIVILEGE,DELETE_COMPANY, DELETE_VENDER,DELETE_ITEM, DELETE_ITEM_TYPE, DELETE_ITEM_UNIT));
        
        final List<Privilege> superBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(LOGIN_PRIVILEGE));// = new ArrayList<Privilege>(Arrays.asList(readPrivilege,writePrivilege, passwordPrivilege,updatePrivilege,deletePrivilege));
        //Adding read privileges
        for(Privilege privilege:readBusinessPrivileges) {
        	superBusinessPrivileges.add(privilege);
        }
        //Adding add privileges
        for(Privilege privilege:addBusinessPrivileges) {
        	superBusinessPrivileges.add(privilege);
        }
        //Adding update privileges
        for(Privilege privilege:updateBusinessPrivileges) {
        	superBusinessPrivileges.add(privilege);
        }
        //Adding delete privileges
        for(Privilege privilege:deleteBusinessPrivileges) {
        	superBusinessPrivileges.add(privilege);
        }
        final List<Privilege> adminBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege,writePrivilege, CHANGE_PASSWORD_PRIVILEGE,updatePrivilege,deletePrivilege));
        final List<Privilege> userBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege,writePrivilege,updatePrivilege));
        final List<Privilege> subUserBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege, writePrivilege));
        final List<Privilege> guestBusinessPrivileges = new ArrayList<Privilege>(Arrays.asList(readPrivilege));

        final Role superRole = createRoleIfNotFound(env.getRequiredProperty("user.role.super"), superBusinessPrivileges);//createRoleIfNotFound("ROLE_BUSINESS_SUPER", superBusinessPrivileges);
//        final Role adminRole = createRoleIfNotFound("ROLE_BUSINESS_ADMIN", adminPrivileges);
//        final Role userRole = createRoleIfNotFound("ROLE_BUSINESS_USER", userPrivileges);
//        final Role subUserRole = createRoleIfNotFound("ROLE_BUSINESS_SUB_USER", subUserPrivileges);
//        final Role guestRole = createRoleIfNotFound("ROLE_BUSINESS_GUEST", guestPrivileges);

        final String EGUCATION = "TEYPE_EGUCATION";
        final String WELFARE = "TEYPE_WELFARE";
        final String APPOINTMENT = "APPOINTMENT";
        final String JOB= "JOB";
        final String HR = "HR";
//        
        // == create initial user
//        createUserIfNotFound("super@super.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(superRole)), new ArrayList<Type>(Arrays.asList(superType)));
//        createUserIfNotFound("admin@admin.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(adminRole)), new ArrayList<Type>(Arrays.asList(adminType)));
//        createUserIfNotFound("user@user.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(userRole)), new ArrayList<Type>(Arrays.asList(userType)));
//        createUserIfNotFound("subuser@subuser.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(subUserRole)), new ArrayList<Type>(Arrays.asList(subUserType)));
//        createUserIfNotFound("guest@guest.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(guestRole)), new ArrayList<Type>(Arrays.asList(guestType)));

        // == create initial user
        createUserIfNotFound("03114499660", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(superRole)),USER_TYPE);
//        createUserIfNotFound("admin@admin.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(adminRole)));
//        createUserIfNotFound("user@user.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(userRole)));
//        createUserIfNotFound("subuser@subuser.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(subUserRole)));
//        createUserIfNotFound("guest@guest.com", "Test", "Test", "test", new ArrayList<Role>(Arrays.asList(guestRole)));
*/        
        alreadySetup = true;
    }

//    @Transactional
//    private final Privilege createPrivilegeIfNotFound(final String name,final Collection<Service> services) {
//        Privilege privilege = privilegeRepository.findByName(name);
//        if (privilege == null) {
//            privilege = new Privilege(name);
//            privilege.setServices(services);
//            privilege = privilegeRepository.save(privilege);
//        }
//        return privilege;
//    }

    @Transactional
    private final Privilege createPrivilegeIfNotFound(final String name) {
        Privilege privilege = privilegeRepository.findByName(name);
        if (privilege == null) {
            privilege = new Privilege(name);
            privilege = privilegeRepository.save(privilege);
        }
        return privilege;
    }

//    @Transactional
//    private final Service createServiceeIfNotFound(final String name) {
//        Service service = serviceRepository.findByName(name);
//        if (service == null) {
//            service = new Service(name);
//            service = serviceRepository.save(service);
//        }
//        return service;
//    }
//
//    @Transactional
//    private final Service createServiceIfNotFound(final String name) {
//    	Service service = serviceRepository.findByName(name);
//        if (service == null) {
//            service = new Service(name);
//            service = serviceRepository.save(service);
//        }
//        return service;
//    }

    @Transactional
    private final Role createRoleIfNotFound(final String name, final Collection<Privilege> privileges) {
        Role role = roleRepository.findByName(name);
        if (role == null) {
            role = new Role(name);
        }
        role.setPrivileges(privileges);
        role = roleRepository.save(role);
        return role;
    }

//    @Transactional
//    private final Type createTypeIfNotFound(final String name) {
//    	Type type = typeRepository.findByName(name);
//        if (type == null) {
//            type = new Type(name);
//        }
//        type = typeRepository.save(type);
//        return type;
//    }

//    @Transactional
//    private final User createUserIfNotFound(final String email, final String firstName, final String lastName, final String password, final Collection<Role> roles
//    		, final Collection<Type> types) {
//        User user = userRepository.findByEmail(email);
//        if (user == null) {
//            user = new User();
//            user.setFirstName(firstName);
//            user.setLastName(lastName);
//            user.setPassword(passwordEncoder.encode(password));
//            user.setEmail(email);
//            user.setEnabled(true);
//        }
//        user.setRoles(roles);
//        user.setTypes(types);
//        user = userRepository.save(user);
//        return user;
//    }

    @Transactional
    private final User createUserIfNotFound(final String email, final String firstName, final String lastName, final String password, final Collection<Role> roles,final String type) {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            user = new User();
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setPassword(passwordEncoder.encode(password));
            user.setEmail(email);
            user.setEnabled(true);
            user.setUserType(type);
        }
        user.setRoles(roles);
        user = userRepository.save(user);
        return user;
    }

}