package com.booking.movie.service;

import com.booking.movie.entity.UserEntity;
import com.booking.movie.exception.InvalidRequestException;
import com.booking.movie.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserEntity getUserById(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidRequestException(
                    "User ID must be a positive number"
            );
        }

        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found with ID: " + id
                ));
    }
}
