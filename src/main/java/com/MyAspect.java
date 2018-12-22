package com;

import java.util.List;

import javax.servlet.http.HttpSessionBindingEvent;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.ServletRequestDataBinder;

import com.persistence.model.User;
import com.security.LoggedUser;

import ch.qos.logback.core.joran.action.TimestampAction;

@Component
@Aspect
public class MyAspect {

//	private final Log log = LogFactory.getLog(this.getClass());
	private final Logger LOGGER = LoggerFactory.getLogger(getClass());
//	@Around("execution(* com.web..*.*(..))")
	public Object logTimeMethod(ProceedingJoinPoint joinPoint) throws Throwable {

			StopWatch stopWatch = new StopWatch();
			stopWatch.start();

			Object retVal = joinPoint.proceed();

			stopWatch.stop();

			StringBuffer logMessage = new StringBuffer();
			logMessage.append(joinPoint.getTarget().getClass().getName());
			logMessage.append(".");
			logMessage.append(joinPoint.getSignature().getName());
			logMessage.append("(");
			// append args
			Object[] args = joinPoint.getArgs();
			for (int i = 0; i < args.length; i++) {
				logMessage.append(args[i]).append(",");
			}
			if (args.length > 0) {
				logMessage.deleteCharAt(logMessage.length() - 1);
			}

			logMessage.append(")");
			logMessage.append(" execution time: ");
			logMessage.append(stopWatch.getTotalTimeMillis());
			logMessage.append(" ms");
			LOGGER.info(logMessage.toString());
			return retVal;
	}

//	@AfterReturning("execution(* com.security..*.*(..))")
	public void beforeWebMethodExecution(JoinPoint joinPoint) {
	    Object[] args = joinPoint.getArgs();
	    String methodName = joinPoint.getSignature().getName();
	    User principal = (User)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
	    TimestampAction timestamp = new TimestampAction();//(new java.util.Date().getTime());
	    // only log those methods called by an end user
	    if(principal.getEmail() != null) {
	        for(Object o : args) {
	            Boolean doInspect = true;
	            if(o instanceof ServletRequestDataBinder) doInspect = false;
	            if(o instanceof ExtendedModelMap) doInspect = false;
	            if(doInspect) {
	            	StringBuffer sb = new StringBuffer();
//	                if(o instanceof BaseForm ) {
	                    // only show form objects
//	                    AuditRecord ar = new AuditRecord();
	                	sb.append(principal.getEmail());
	                	sb.append(o.getClass().getCanonicalName());
	                	sb.append(methodName);
	                	sb.append(o.toString());
	                	sb.append(timestamp);
//	                    auditRecordDAO.save(ar);
//	                }
	                System.out.print(sb.toString());
	            }
	        }
	    }
	}
	
}