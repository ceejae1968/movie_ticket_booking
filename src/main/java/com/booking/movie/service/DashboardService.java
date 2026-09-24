package com.booking.movie.service;

import com.booking.movie.exception.InvalidRequestException;
import com.booking.movie.pojos.ShowResponse;
import com.booking.movie.repositories.ShowDetailsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    @Autowired
    private ShowDetailsRepository showDetailsRepository;

    @Transactional(readOnly = true)
    public Page<ShowResponse> getHomepage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidRequestException(
                    "Page must be non-negative and size between 1 and 100");
        }
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return showDetailsRepository.findAll(pageable)
                .map(show -> new ShowResponse(show.getId(), show.getTitle(),
                        show.getDescription(), show.getActive()));
    }
}
