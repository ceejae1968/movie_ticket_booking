package com.booking.movie.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"user_name"}
        )
)
@Data
public class UserEntity extends BaseEntity{

    private static final long serialVersionUID = 1L;

    @Column
    private String userName;

    @Column
    private String fullName;

}
