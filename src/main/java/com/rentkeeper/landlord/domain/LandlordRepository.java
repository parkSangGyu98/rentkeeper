package com.rentkeeper.landlord.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LandlordRepository extends JpaRepository<Landlord, Long> {

	boolean existsByAuthProviderAndEmail(AuthProvider authProvider, String email);

	Optional<Landlord> findByAuthProviderAndEmail(AuthProvider authProvider, String email);

}
