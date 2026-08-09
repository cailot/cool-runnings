package hyung.jin.seo.coolrunnings.repository;

import hyung.jin.seo.coolrunnings.model.LotteryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for public.archive_entry.
 */
public class LotteryResultRepository {

    private static final Logger log = LoggerFactory.getLogger(LotteryResultRepository.class);

    private final String url;
    private final String username;
    private final String password;

    public LotteryResultRepository(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    public List<LotteryResult> findAllByOrderByDrawDesc() {
        String sql = "SELECT * FROM public.archive_entry ORDER BY draw DESC";
        List<LotteryResult> results = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load archive_entry rows", e);
        }
        return results;
    }

    public Optional<LotteryResult> findFirstByOrderByDrawDesc() {
        String sql = "SELECT * FROM public.archive_entry ORDER BY draw DESC LIMIT 1";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load latest archive_entry", e);
        }
    }

    public Optional<LotteryResult> findByDraw(Integer draw) {
        String sql = "SELECT * FROM public.archive_entry WHERE draw = ?";
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, draw);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find archive_entry by draw=" + draw, e);
        }
    }

    public LotteryResult save(LotteryResult result) {
        LocalDateTime now = LocalDateTime.now();
        if (result.getCreatedAt() == null) {
            result.setCreatedAt(now);
        }
        result.setUpdatedAt(now);

        if (result.getId() == null) {
            return insert(result);
        }
        return update(result);
    }

    private LotteryResult insert(LotteryResult result) {
        String sql = """
                INSERT INTO public.archive_entry (
                    bonus_number_1, bonus_number_2, created_at, draw, draw_date, even_count, from_last,
                    high_count, low_count, odd_count, range_11_20, range_1_10, range_21_30, range_31_40,
                    range_41_50, updated_at, winning_number_1, winning_number_2, winning_number_3,
                    winning_number_4, winning_number_5, winning_number_6, winning_number_7
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                RETURNING id
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindMutableColumns(ps, result);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Insert returned no id");
                }
                result.setId(rs.getLong(1));
                log.debug("Inserted archive_entry draw={} id={}", result.getDraw(), result.getId());
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert archive_entry draw=" + result.getDraw(), e);
        }
    }

    private LotteryResult update(LotteryResult result) {
        String sql = """
                UPDATE public.archive_entry SET
                    bonus_number_1=?, bonus_number_2=?, created_at=?, draw=?, draw_date=?, even_count=?, from_last=?,
                    high_count=?, low_count=?, odd_count=?, range_11_20=?, range_1_10=?, range_21_30=?, range_31_40=?,
                    range_41_50=?, updated_at=?, winning_number_1=?, winning_number_2=?, winning_number_3=?,
                    winning_number_4=?, winning_number_5=?, winning_number_6=?, winning_number_7=?
                WHERE id=?
                """;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindMutableColumns(ps, result);
            ps.setLong(24, result.getId());
            ps.executeUpdate();
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update archive_entry id=" + result.getId(), e);
        }
    }

    private void bindMutableColumns(PreparedStatement ps, LotteryResult r) throws SQLException {
        setNullableInt(ps, 1, r.getBonusNumber1());
        setNullableInt(ps, 2, r.getBonusNumber2());
        ps.setTimestamp(3, Timestamp.valueOf(r.getCreatedAt()));
        ps.setInt(4, r.getDraw());
        ps.setObject(5, r.getDrawDate());
        setNullableInt(ps, 6, r.getEvenCount());
        ps.setString(7, r.getFromLast());
        setNullableInt(ps, 8, r.getHighCount());
        setNullableInt(ps, 9, r.getLowCount());
        setNullableInt(ps, 10, r.getOddCount());
        setNullableInt(ps, 11, r.getRange11To20());
        setNullableInt(ps, 12, r.getRange1To10());
        setNullableInt(ps, 13, r.getRange21To30());
        setNullableInt(ps, 14, r.getRange31To40());
        setNullableInt(ps, 15, r.getRange41To50());
        ps.setTimestamp(16, Timestamp.valueOf(r.getUpdatedAt()));
        setNullableInt(ps, 17, r.getWinningNumber1());
        setNullableInt(ps, 18, r.getWinningNumber2());
        setNullableInt(ps, 19, r.getWinningNumber3());
        setNullableInt(ps, 20, r.getWinningNumber4());
        setNullableInt(ps, 21, r.getWinningNumber5());
        setNullableInt(ps, 22, r.getWinningNumber6());
        setNullableInt(ps, 23, r.getWinningNumber7());
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static LotteryResult mapRow(ResultSet rs) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return LotteryResult.builder()
                .id(rs.getLong("id"))
                .draw(rs.getInt("draw"))
                .drawDate(rs.getObject("draw_date", java.time.LocalDate.class))
                .winningNumber1(getInteger(rs, "winning_number_1"))
                .winningNumber2(getInteger(rs, "winning_number_2"))
                .winningNumber3(getInteger(rs, "winning_number_3"))
                .winningNumber4(getInteger(rs, "winning_number_4"))
                .winningNumber5(getInteger(rs, "winning_number_5"))
                .winningNumber6(getInteger(rs, "winning_number_6"))
                .winningNumber7(getInteger(rs, "winning_number_7"))
                .bonusNumber1(getInteger(rs, "bonus_number_1"))
                .bonusNumber2(getInteger(rs, "bonus_number_2"))
                .fromLast(rs.getString("from_last"))
                .lowCount(getInteger(rs, "low_count"))
                .highCount(getInteger(rs, "high_count"))
                .oddCount(getInteger(rs, "odd_count"))
                .evenCount(getInteger(rs, "even_count"))
                .range1To10(getInteger(rs, "range_1_10"))
                .range11To20(getInteger(rs, "range_11_20"))
                .range21To30(getInteger(rs, "range_21_30"))
                .range31To40(getInteger(rs, "range_31_40"))
                .range41To50(getInteger(rs, "range_41_50"))
                .createdAt(created == null ? null : created.toLocalDateTime())
                .updatedAt(updated == null ? null : updated.toLocalDateTime())
                .build();
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
