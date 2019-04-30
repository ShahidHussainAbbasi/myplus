import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public class Test {

	public static boolean isEmpty(Number number){
		if( number == null || number.toString()==null ){
			return true;
		}
		return false;
	} 
	public static Connection getConnection() throws Exception {
	    String driver = "com.mysql.cj.jdbc.Driver";
	    String url = "jdbc:mysql://localhost:3306/myplusdb";
	    String username = "root";
	    String password = "root";

	    Class.forName(driver);
	    Connection conn = DriverManager.getConnection(url, username, password);
	    return conn;
	  }
	
    public static void main(final String args[]) {
    	Result result = JUnitCore.runClasses(Test.class);
		
        for (Failure failure : result.getFailures()) {
           System.out.println(failure.toString());
        }
  		
        System.out.println(result.wasSuccessful());    	
    	//importGuardian();
//    	Integer i = new Integer(2);
//    	boolean check = isEmpty(i);
//    	System.out.println(check);
    }

    @org.junit.Test
    public void importGuardian() {
    	String str = "Junit is working fine";
        assertEquals("Junit is working fine",str);
     
	    PreparedStatement pstmt = null;
	    Connection conn = null;
		
		FileInputStream fis=null;
		try {
	      conn = getConnection();
	      conn.setAutoCommit(true);
			fis = new FileInputStream(new File("C:\\Users\\sabbasi\\Desktop\\gardians.xlsx"));
		    XSSFWorkbook workbook = new XSSFWorkbook (fis);
			
		    XSSFSheet sheet = workbook.getSheetAt(0);
		    Iterator ite = sheet.rowIterator();
		      pstmt = conn.prepareStatement("insert into myplusdb.guardian(dated,email,gender,mobile,name,occupation,perm_address,phone,relation,status,temp_address,updated,user_id,cnic) "
			      		+ "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
		    
		    while(ite.hasNext()){
			    Boolean update = true;
		        Row row = (Row) ite.next();
		        if(row.getRowNum()==0)
		        	continue;
		        if(row.getRowNum()==155)
		        	break;
		        for(int i=1; i<row.getLastCellNum(); i++) {
		        	//validate if already exist
		        	PreparedStatement pst = conn.prepareStatement("select * from myplusdb.guardian where name = ? AND user_id=? ");
		        	pst.setString(1, row.getCell(5).getStringCellValue());
		        	pst.setInt(2, 517);
	      	      	ResultSet rs = pst.executeQuery();
		  		  	if(rs.next()) {
		  		  	update=false;
		  		  		break;
		  		  	}
			        Cell cell = row.getCell(i);
	//		            Cell c = cite.next();
			        if(i==13) {
			        	pstmt.setInt(i, 517);
			        }
			        else {
			        	String val=cell==null?null:cell.getStringCellValue();
			        	if(val == null || val.equals(""))
			        		val = null;
				        pstmt.setString(i, val);
			        }
		        }
		        System.out.println(pstmt.toString());
		        if(update)
		        	pstmt.executeUpdate();
		    }
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}finally {
		    try {
		    	if(fis!=null)
				fis.close();
		    	try {
					pstmt.close();
			    	conn.close();
				} catch (SQLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}		
		}    
	}
    
}
