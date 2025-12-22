package hyung.jin.seo.coolrunnings.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import hyung.jin.seo.coolrunnings.model.LotteryResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 복권 당첨 결과 Repository
 */
@Repository
public interface LotteryResultRepository extends JpaRepository<LotteryResult, Long> {

    /**
     * Draw 번호로 조회
     */
    Optional<LotteryResult> findByDraw(Integer draw);

    /**
     * 추첨일로 조회
     */
    Optional<LotteryResult> findByDrawDate(LocalDate drawDate);

    /**
     * 날짜 범위로 조회
     */
    List<LotteryResult> findByDrawDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * 최신순으로 조회
     */
    List<LotteryResult> findAllByOrderByDrawDateDesc();

    /**
     * Draw 번호 내림차순으로 조회
     */
    List<LotteryResult> findAllByOrderByDrawDesc();

    /**
     * 최신 회차 조회 (Draw 번호가 가장 큰 것)
     */
    @Query("SELECT lr FROM LotteryResult lr ORDER BY lr.draw DESC")
    List<LotteryResult> findLatestDraws();

    /**
     * 최신 회차 조회 (Draw 번호가 가장 큰 것) - 첫 번째만
     */
    Optional<LotteryResult> findFirstByOrderByDrawDesc();

    /**
     * 특정 번호가 포함된 당첨 결과 조회
     */
    @Query("SELECT lr FROM LotteryResult lr WHERE " +
           ":number = lr.winningNumber1 OR :number = lr.winningNumber2 OR :number = lr.winningNumber3 OR " +
           ":number = lr.winningNumber4 OR :number = lr.winningNumber5 OR :number = lr.winningNumber6 OR " +
           ":number = lr.winningNumber7 OR :number = lr.bonusNumber1 OR :number = lr.bonusNumber2")
    List<LotteryResult> findByNumberContaining(Integer number);
}

