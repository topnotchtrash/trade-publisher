package com.traderecon.tradepublisher.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationRequest {

    @NotNull(message = "Trade count is required")
    @Min(value = 1, message = "Trade count must be at least 1")
    @Max(value = 1_000_000, message = "Trade count cannot exceed 1,000,000")
    private Integer tradeCount;

    @NotNull(message = "Trade distribution is required")
    private Map<String, Integer> tradeDistribution;

    @NotNull(message = "Profile distribution is required")
    private Map<String, Integer> profileDistribution;

    public void validate() {
        validateDistribution(tradeDistribution, "Trade distribution");
        validateDistribution(profileDistribution, "Profile distribution");
    }

    private void validateDistribution(Map<String, Integer> distribution, String name) {
        if (distribution == null || distribution.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }

        int sum = distribution.values().stream()
                .mapToInt(Integer::intValue)
                .sum();

        if (sum != 100) {
            throw new IllegalArgumentException(name + " percentages must sum to 100, got: " + sum);
        }

        distribution.values().forEach(value -> {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException(name + " percentages must be between 0 and 100");
            }
        });
    }
}