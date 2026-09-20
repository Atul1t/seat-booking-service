package com.atulit.seatbooking.service;

import com.atulit.seatbooking.domain.Seat;
import com.atulit.seatbooking.domain.SeatStatus;
import com.atulit.seatbooking.domain.Show;
import com.atulit.seatbooking.dto.SeatMapView;
import com.atulit.seatbooking.dto.ShowSummaryView;
import com.atulit.seatbooking.dto.SeatView;
import com.atulit.seatbooking.exception.NotFoundException;
import com.atulit.seatbooking.repository.SeatRepository;
import com.atulit.seatbooking.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    /**
     * Creates a show and lays out its seat grid: rows are labelled A, B, C ... and each
     * row has {@code seatsPerRow} seats numbered from 1.
     */
    @Transactional
    public Show createShow(String title, Instant startsAt, int rows, int seatsPerRow) {
        if (rows < 1 || rows > 26) {
            throw new IllegalArgumentException("rows must be between 1 and 26");
        }
        if (seatsPerRow < 1 || seatsPerRow > 50) {
            throw new IllegalArgumentException("seatsPerRow must be between 1 and 50");
        }

        Show show = showRepository.save(new Show(title, startsAt));

        List<Seat> seats = new ArrayList<>(rows * seatsPerRow);
        for (int r = 0; r < rows; r++) {
            String rowLabel = String.valueOf((char) ('A' + r));
            for (int n = 1; n <= seatsPerRow; n++) {
                seats.add(new Seat(show, rowLabel, n));
            }
        }
        seatRepository.saveAll(seats);

        return show;
    }

    @Transactional(readOnly = true)
    public Show getShow(Long showId) {
        return showRepository.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
    }

    /**
     * Shows plus a live availability count, for the browse screen.
     *
     * <p>This issues two count queries per show, so it is O(n) round trips. Fine for a
     * handful of shows; the fix at scale is one grouped query returning counts per show
     * in a single pass.
     */
    @Transactional(readOnly = true)
    public List<ShowSummaryView> listShows() {
        return showRepository.findAll().stream()
                .sorted(Comparator.comparing(Show::getStartsAt))
                .map(show -> new ShowSummaryView(
                        show.getId(),
                        show.getTitle(),
                        show.getStartsAt(),
                        seatRepository.countByShowId(show.getId()),
                        seatRepository.countByShowIdAndStatus(show.getId(), SeatStatus.AVAILABLE)))
                .toList();
    }

    @Transactional(readOnly = true)
    public SeatMapView getSeatMap(Long showId) {
        Show show = getShow(showId);
        List<SeatView> seats = seatRepository
                .findByShowIdOrderByRowLabelAscSeatNumberAsc(showId)
                .stream()
                .map(SeatView::of)
                .toList();
        long available = seats.stream().filter(s -> s.status() == SeatStatus.AVAILABLE).count();
        return new SeatMapView(show.getId(), show.getTitle(), show.getStartsAt(), available, seats);
    }
}
