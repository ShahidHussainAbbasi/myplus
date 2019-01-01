
public class Test {

	public static boolean isEmpty(Number number){
		if( number == null || number.toString()==null ){
			return true;
		}
		return false;
	} 
	
    public static void main(final String args[]) {
    	Integer i = new Integer(2);
    	boolean check = isEmpty(i);
    	System.out.println(check);
    }

}
