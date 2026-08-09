package hyung.jin.seo.coolrunnings.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Set for Life lottery result (maps to public.archive_entry).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotteryResult {

    private Long id;
    private Integer draw;
    private LocalDate drawDate;
    private Integer winningNumber1;
    private Integer winningNumber2;
    private Integer winningNumber3;
    private Integer winningNumber4;
    private Integer winningNumber5;
    private Integer winningNumber6;
    private Integer winningNumber7;
    private Integer bonusNumber1;
    private Integer bonusNumber2;
    private String fromLast;
    private Integer lowCount;
    private Integer highCount;
    private Integer oddCount;
    private Integer evenCount;
    private Integer range1To10;
    private Integer range11To20;
    private Integer range21To30;
    private Integer range31To40;
    private Integer range41To50;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Not persisted. */
    private Integer sum;
    /** Not persisted. */
    private Double average;
    /** Not persisted. */
    private Integer max;
    /** Not persisted. */
    private Integer min;
}
