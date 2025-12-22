package hyung.jin.seo.coolrunnings.repository;

import org.hibernate.dialect.MySQL55Dialect;

public class CoolRunningsMySQLDialect extends MySQL55Dialect{

    @Override
    public String getTableTypeString(){
        return " ENGINE=InnoDB";
    }
    
}
