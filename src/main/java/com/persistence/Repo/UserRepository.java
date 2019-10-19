package com.persistence.Repo;

import com.persistence.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {
   
	User findByEmail(String email);

    @Override
    void delete(User user);
    
//	@Query(value = "SELECT count(*) as 'freshStudent',(SELECT count(*) FROM student ) as 'allStudent' FROM student WHERE enroll_date >= :lastMonth", nativeQuery = true)
//	Object getDashboardData(String lastMonth);


}
