-- Set for Life 복권 결과 테이블 생성
-- CSV 파일의 모든 정보를 하나의 테이블에 저장

DROP TABLE IF EXISTS `archive_entry`;

CREATE TABLE `archive_entry` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `draw` INT NOT NULL COMMENT '추첨 번호',
    `draw_date` DATE NOT NULL COMMENT '추첨일',
    `winning_number_1` INT COMMENT '당첨 번호 1',
    `winning_number_2` INT COMMENT '당첨 번호 2',
    `winning_number_3` INT COMMENT '당첨 번호 3',
    `winning_number_4` INT COMMENT '당첨 번호 4',
    `winning_number_5` INT COMMENT '당첨 번호 5',
    `winning_number_6` INT COMMENT '당첨 번호 6',
    `winning_number_7` INT COMMENT '당첨 번호 7',
    `bonus_number_1` INT COMMENT '보너스 번호 1',
    `bonus_number_2` INT COMMENT '보너스 번호 2',
    `from_last` VARCHAR(100) COMMENT '이전 추첨과 중복된 번호들 (쉼표로 구분)',
    `low_count` INT COMMENT '낮은 번호 개수',
    `high_count` INT COMMENT '높은 번호 개수',
    `odd_count` INT COMMENT '홀수 개수',
    `even_count` INT COMMENT '짝수 개수',
    `range_1_10` INT COMMENT '1-10 범위 번호 개수',
    `range_11_20` INT COMMENT '11-20 범위 번호 개수',
    `range_21_30` INT COMMENT '21-30 범위 번호 개수',
    `range_31_40` INT COMMENT '31-40 범위 번호 개수',
    `range_41_50` INT COMMENT '41-50 범위 번호 개수',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_draw` (`draw`),
    KEY `idx_draw_date` (`draw_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Set for Life 복권 당첨 결과';
