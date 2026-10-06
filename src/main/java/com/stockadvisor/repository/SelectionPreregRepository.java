package com.stockadvisor.repository;

import com.stockadvisor.domain.SelectionPrereg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SelectionPreregRepository extends JpaRepository<SelectionPrereg, Long> {

    boolean existsByName(String name);

    List<SelectionPrereg> findAllByOrderByIdAsc();
}
