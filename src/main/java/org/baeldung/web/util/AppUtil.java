/**
 * 
 */
package org.baeldung.web.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author sabbasi
 *
 */
public class AppUtil {

    public static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    public static final DateTimeFormatter dateformatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");

   	//Get current date time
    public static String todayDateStr() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(AppUtil.dateformatter);
    }

    //Get current date time
    public static String todayDateTimeStr() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(AppUtil.dateTimeFormatter);
    }

}
