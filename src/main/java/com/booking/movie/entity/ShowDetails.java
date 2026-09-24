package com.booking.movie.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(
        name = "show_details"
)
@Data
public class ShowDetails extends BaseEntity{

    private static final long serialVersionUID = 1L;

    @Column
    private Boolean active;
    @Column
    private String title;
    @Column
    private String description;
}
