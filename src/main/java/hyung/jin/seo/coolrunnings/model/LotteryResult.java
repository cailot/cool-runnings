package hyung.jin.seo.coolrunnings.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.Transient;

/**
 * Set for Life 복권 당첨 결과 엔티티
 * CSV 파일 구조에 맞춘 전체 정보를 하나의 테이블에 저장
 */
@Entity
@Table(name = "archive_entry")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotteryResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 추첨 번호 (Draw)
     */
    @Column(nullable = false, unique = true, name = "draw")
    private Integer draw;

    /**
     * 추첨일 (Date)
     */
    @Column(nullable = false, name = "draw_date")
    private LocalDate drawDate;

    /**
     * 당첨 번호 1
     */
    @Column(name = "winning_number_1")
    private Integer winningNumber1;

    /**
     * 당첨 번호 2
     */
    @Column(name = "winning_number_2")
    private Integer winningNumber2;

    /**
     * 당첨 번호 3
     */
    @Column(name = "winning_number_3")
    private Integer winningNumber3;

    /**
     * 당첨 번호 4
     */
    @Column(name = "winning_number_4")
    private Integer winningNumber4;

    /**
     * 당첨 번호 5
     */
    @Column(name = "winning_number_5")
    private Integer winningNumber5;

    /**
     * 당첨 번호 6
     */
    @Column(name = "winning_number_6")
    private Integer winningNumber6;

    /**
     * 당첨 번호 7
     */
    @Column(name = "winning_number_7")
    private Integer winningNumber7;

    /**
     * 보너스 번호 1
     */
    @Column(name = "bonus_number_1")
    private Integer bonusNumber1;

    /**
     * 보너스 번호 2
     */
    @Column(name = "bonus_number_2")
    private Integer bonusNumber2;

    /**
     * 이전 추첨과 중복된 번호들 (From Last) - 쉼표로 구분된 문자열
     */
    @Column(name = "from_last", length = 100)
    private String fromLast;

    /**
     * 낮은 번호 개수 (Low)
     */
    @Column(name = "low_count")
    private Integer lowCount;

    /**
     * 높은 번호 개수 (High)
     */
    @Column(name = "high_count")
    private Integer highCount;

    /**
     * 홀수 개수 (Odd)
     */
    @Column(name = "odd_count")
    private Integer oddCount;

    /**
     * 짝수 개수 (Even)
     */
    @Column(name = "even_count")
    private Integer evenCount;

    /**
     * 1-10 범위 번호 개수
     */
    @Column(name = "range_1_10")
    private Integer range1To10;

    /**
     * 11-20 범위 번호 개수
     */
    @Column(name = "range_11_20")
    private Integer range11To20;

    /**
     * 21-30 범위 번호 개수
     */
    @Column(name = "range_21_30")
    private Integer range21To30;

    /**
     * 31-40 범위 번호 개수
     */
    @Column(name = "range_31_40")
    private Integer range31To40;

    /**
     * 41-50 범위 번호 개수 (실제로는 41-44)
     */
    @Column(name = "range_41_50")
    private Integer range41To50;

    /**
     * 생성일시
     */
    @Column(nullable = false, updatable = false, name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 수정일시
     */
    @Column(nullable = false, name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 당첨 번호들의 합계 (DB에 저장되지 않음, 나중에 활용 가능)
     */
    @Transient
    private Integer sum;

    /**
     * 당첨 번호들의 평균 (DB에 저장되지 않음, 나중에 활용 가능)
     */
    @Transient
    private Double average;

    /**
     * 당첨 번호들 중 최대값 (DB에 저장되지 않음, 나중에 활용 가능)
     */
    @Transient
    private Integer max;

    /**
     * 당첨 번호들 중 최소값 (DB에 저장되지 않음, 나중에 활용 가능)
     */
    @Transient
    private Integer min;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

