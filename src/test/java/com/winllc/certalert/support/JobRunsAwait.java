package com.winllc.certalert.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Waits for a job started by hand - which answers 202 with a run to ask after - to finish,
 * the way the page does: by asking {@code statusUrl} until the run says it has.
 */
public final class JobRunsAwait {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private JobRunsAwait() {}

    /**
     * @param started the 202 that started the run
     * @param as who asks after it
     * @return the finished run, as {@code /api/v1/jobs/{id}} reports it
     */
    public static ResultActions finished(MockMvc mockMvc, ResultActions started, RequestPostProcessor as)
            throws Exception {
        MvcResult accepted = started.andExpect(status().isAccepted()).andReturn();
        String statusUrl = JsonPath.read(accepted.getResponse().getContentAsString(), "$.statusUrl");
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            ResultActions poll = mockMvc.perform(get(statusUrl).with(as)).andExpect(status().isOk());
            Boolean finished = JsonPath.read(poll.andReturn().getResponse().getContentAsString(), "$.finished");
            if (Boolean.TRUE.equals(finished)) {
                return poll;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("The run at " + statusUrl + " had not finished after " + TIMEOUT);
            }
            Thread.sleep(50);
        }
    }
}
