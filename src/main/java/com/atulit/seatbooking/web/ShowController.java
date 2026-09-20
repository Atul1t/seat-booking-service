package com.atulit.seatbooking.web;

import com.atulit.seatbooking.dto.CreateShowRequest;
import com.atulit.seatbooking.dto.SeatMapView;
import com.atulit.seatbooking.dto.ShowSummaryView;
import com.atulit.seatbooking.dto.ShowView;
import com.atulit.seatbooking.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    public ResponseEntity<ShowView> create(@Valid @RequestBody CreateShowRequest request) {
        ShowView show = ShowView.of(showService.createShow(
                request.title(), request.startsAt(), request.rows(), request.seatsPerRow()));
        return ResponseEntity
                .created(URI.create("/api/shows/" + show.id()))
                .body(show);
    }

    /** Public: the browse screen works without an account. */
    @GetMapping
    public List<ShowSummaryView> list() {
        return showService.listShows();
    }

    @GetMapping("/{showId}/seats")
    public SeatMapView seatMap(@PathVariable Long showId) {
        return showService.getSeatMap(showId);
    }
}
