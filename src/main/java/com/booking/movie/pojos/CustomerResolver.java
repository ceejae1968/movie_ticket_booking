package com.booking.movie.pojos;

import java.util.UUID;

public interface CustomerResolver {
    UUID requireCustomerId(String username);
}