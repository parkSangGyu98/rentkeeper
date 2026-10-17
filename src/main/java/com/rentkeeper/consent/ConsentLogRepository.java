package com.rentkeeper.consent;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface ConsentLogRepository extends JpaRepository<ConsentLog, Long> {

	List<ConsentLog> findByLandlordIdOrderByAgreedAtAsc(long landlordId);

}
