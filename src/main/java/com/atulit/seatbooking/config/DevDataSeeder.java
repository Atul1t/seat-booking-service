package com.atulit.seatbooking.config;

import com.atulit.seatbooking.repository.ShowRepository;
import com.atulit.seatbooking.service.ShowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Puts a few shows in an empty database so the client has something to browse.
 *
 * <p>Excluded from the test profiles: tests create their own fixtures and would rather
 * not find surprise rows.
 */
@Component
@Profile("!test & !postgres")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final ShowRepository showRepository;
    private final ShowService showService;

    public DevDataSeeder(ShowRepository showRepository, ShowService showService) {
        this.showRepository = showRepository;
        this.showService = showService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (showRepository.count() > 0) {
            return;
        }
        Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.DAYS);

        showService.createShow("Interstellar", base.plus(2, ChronoUnit.HOURS), 6, 10);
        showService.createShow("Dune: Part Two", base.plus(5, ChronoUnit.HOURS), 5, 8);
        showService.createShow("Arrival", base.plus(1, ChronoUnit.DAYS), 4, 6);
        showService.createShow("Blade Runner 2049", base.plus(2, ChronoUnit.DAYS), 8, 12);

        log.info("Seeded {} demo shows", showRepository.count());
    }
}
