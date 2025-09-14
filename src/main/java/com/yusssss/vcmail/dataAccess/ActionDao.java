package com.yusssss.vcmail.dataAccess;

import com.yusssss.vcmail.entities.Action;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActionDao extends JpaRepository<Action, String> {
}
