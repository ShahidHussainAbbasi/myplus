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

    public static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

   	//Get current date time
    public static String todayDateStr() {
        LocalDateTime now = LocalDateTime.now();
        return now.format(AppUtil.formatter);
    }
}
