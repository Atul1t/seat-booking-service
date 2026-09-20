package com.atulit.seatbooking.repository;

import com.atulit.seatbooking.domain.Show;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, Long> {
}
