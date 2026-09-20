package com.atulit.seatbooking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walks the API the way a client would, through the real security filter chain.
 *
 * <p>Status codes are pinned deliberately, because they are the only thing a client can
 * act on: 401 means "log in", 403 means "that is not yours", 409 means "someone beat you
 * to it", 410 means "your hold timed out, start again".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeatBookingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;
    private String otherToken;

    @BeforeEach
    void signUpTwoCustomers() throws Exception {
        token = registerAndLogin("Atulit");
        otherToken = registerAndLogin("Someone Else");
    }

    private String registerAndLogin(String displayName) throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        String password = "correct-horse-battery";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"%s"}
                                """.formatted(email, password, displayName)))
                .andExpect(status().isCreated());

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("token").asText();
    }

    /** Attaches a bearer token to a request. */
    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,
                                                    String bearer) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }

    private long createShow(String title, int rows, int seatsPerRow) throws Exception {
        String response = mockMvc.perform(as(post("/api/shows"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","startsAt":"2026-12-01T18:30:00Z","rows":%d,"seatsPerRow":%d}
                                """.formatted(title, rows, seatsPerRow)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(title))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("id").asLong();
    }

    private long firstSeatId(long showId) throws Exception {
        String response = mockMvc.perform(get("/api/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode seats = objectMapper.readTree(response).get("seats");
        return seats.get(0).get("id").asLong();
    }

    private long hold(long showId, long seatId, String bearer) throws Exception {
        String response = mockMvc.perform(as(post("/api/shows/{id}/holds", showId), bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatIds":[%d]}
                                """.formatted(seatId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("holdId").asLong();
    }

    @Test
    void browsingSeatsNeedsNoAccount() throws Exception {
        long showId = createShow("Public Browsing", 2, 2);

        mockMvc.perform(get("/api/shows/{id}/seats", showId))   // no token
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCount").value(4))
                .andExpect(jsonPath("$.seats.length()").value(4))
                .andExpect(jsonPath("$.seats[0].label").value("A1"));
    }

    @Test
    void holdingWithoutATokenIsRejected() throws Exception {
        long showId = createShow("Anonymous", 1, 1);
        long seatId = firstSeatId(showId);

        mockMvc.perform(post("/api/shows/{id}/holds", showId)   // no token
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatIds":[%d]}
                                """.formatted(seatId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void holdThenConfirmReturnsABookingReference() throws Exception {
        long showId = createShow("Happy Path", 1, 2);
        long seatId = firstSeatId(showId);
        long holdId = hold(showId, seatId, token);

        String response = mockMvc.perform(as(post("/api/holds/{id}/confirm", holdId), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0]").value("A1"))
                .andExpect(jsonPath("$.customerName").value("Atulit"))
                .andReturn().getResponse().getContentAsString();

        String reference = objectMapper.readTree(response).get("reference").asText();
        assertThat(reference).startsWith("BK");

        mockMvc.perform(as(get("/api/bookings/{ref}", reference), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Atulit"));
    }

    @Test
    void competingForATakenSeatReturns409WithTheSeatLabel() throws Exception {
        long showId = createShow("Contended", 1, 1);
        long seatId = firstSeatId(showId);
        hold(showId, seatId, token);

        mockMvc.perform(as(post("/api/shows/{id}/holds", showId), otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatIds":[%d]}
                                """.formatted(seatId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("seat_unavailable"))
                .andExpect(jsonPath("$.details[0]").value("A1"));
    }

    @Test
    void confirmingSomebodyElsesHoldIsForbidden() throws Exception {
        long showId = createShow("Not Yours", 1, 1);
        long seatId = firstSeatId(showId);
        long holdId = hold(showId, seatId, token);

        mockMvc.perform(as(post("/api/holds/{id}/confirm", holdId), otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        // The rightful owner is unaffected.
        mockMvc.perform(as(post("/api/holds/{id}/confirm", holdId), token))
                .andExpect(status().isOk());
    }

    @Test
    void myBookingsListsOnlyMine() throws Exception {
        long showId = createShow("Two Customers", 1, 2);
        String response = mockMvc.perform(get("/api/shows/{id}/seats", showId))
                .andReturn().getResponse().getContentAsString();
        JsonNode seats = objectMapper.readTree(response).get("seats");

        long mineHold = hold(showId, seats.get(0).get("id").asLong(), token);
        long theirHold = hold(showId, seats.get(1).get("id").asLong(), otherToken);
        mockMvc.perform(as(post("/api/holds/{id}/confirm", mineHold), token))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/api/holds/{id}/confirm", theirHold), otherToken))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/api/bookings"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seats[0]").value("A1"));
    }

    @Test
    void releasingAHoldReturns204AndFreesTheSeat() throws Exception {
        long showId = createShow("Released", 1, 1);
        long seatId = firstSeatId(showId);
        long holdId = hold(showId, seatId, token);

        mockMvc.perform(as(delete("/api/holds/{id}", holdId), token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/shows/{id}/seats", showId))
                .andExpect(jsonPath("$.availableCount").value(1));
    }

    @Test
    void unknownBookingReferenceReturns404() throws Exception {
        mockMvc.perform(as(get("/api/bookings/{ref}", "BKNOPE1234"), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void anEmptySeatListIsRejectedAsBadRequest() throws Exception {
        long showId = createShow("Validation", 1, 1);

        mockMvc.perform(as(post("/api/shows/{id}/holds", showId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatIds":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
    }
}
