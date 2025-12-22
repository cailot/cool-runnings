#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CSV 파일을 SQL INSERT 문으로 변환하는 스크립트
"""

import csv
import os

def escape_sql_string(value):
    """SQL 문자열 이스케이프"""
    if value is None or value == '':
        return 'NULL'
    # 따옴표 제거 및 이스케이프
    value = str(value).replace("'", "''").replace('"', '')
    return f"'{value}'"

def parse_integer(value):
    """정수로 변환, 실패 시 NULL"""
    if value is None or value == '' or value.strip() == '':
        return 'NULL'
    try:
        return int(value.strip())
    except:
        return 'NULL'

def generate_insert_sql(csv_file_path, output_file_path):
    """CSV 파일을 읽어서 SQL INSERT 문 생성 (1회차부터 순서대로)"""
    
    with open(csv_file_path, 'r', encoding='utf-8') as csvfile:
        reader = csv.reader(csvfile)
        
        # 헤더 건너뛰기
        next(reader)
        
        # 모든 행을 읽어서 리스트에 저장
        all_rows = list(reader)
        
        # 역순으로 정렬 (1회차부터 시작하도록)
        # CSV는 최신(3788)부터 오래된(1) 순서이므로 역순으로 처리
        all_rows.reverse()
        
        sql_statements = []
        sql_statements.append("-- Set for Life 복권 결과 데이터 삽입")
        sql_statements.append("-- CSV 파일에서 자동 생성된 INSERT 문")
        sql_statements.append("-- 1회차(Draw 1)부터 순서대로 저장")
        sql_statements.append("")
        sql_statements.append("USE cool;")
        sql_statements.append("")
        sql_statements.append("-- 기존 데이터 삭제 (선택사항)")
        sql_statements.append("-- DELETE FROM lottery_result;")
        sql_statements.append("")
        sql_statements.append("-- 데이터 삽입 시작 (1회차부터)")
        sql_statements.append("")
        
        count = 0
        for row in all_rows:
            if len(row) < 11:
                continue
            
            try:
                # 필드 파싱
                draw = parse_integer(row[0])
                draw_date = escape_sql_string(row[1]) if row[1] else 'NULL'
                winning_number_1 = parse_integer(row[2])
                winning_number_2 = parse_integer(row[3])
                winning_number_3 = parse_integer(row[4])
                winning_number_4 = parse_integer(row[5])
                winning_number_5 = parse_integer(row[6])
                winning_number_7 = parse_integer(row[7]) if len(row) > 7 else 'NULL'
                winning_number_6 = parse_integer(row[8]) if len(row) > 8 else 'NULL'
                
                # CSV 구조 재확인 필요
                # Draw,Date,Winning Number 1,2,3,4,5,6,7,Bonus Number 1,2,From Last,...
                # 실제로는: Draw,Date,1,2,3,4,5,6,7,Bonus1,Bonus2,From Last,...
                
                # 다시 정확하게 파싱
                draw = parse_integer(row[0])
                draw_date = row[1] if row[1] else 'NULL'
                if draw_date != 'NULL':
                    draw_date = f"'{draw_date}'"
                
                winning_number_1 = parse_integer(row[2])
                winning_number_2 = parse_integer(row[3])
                winning_number_3 = parse_integer(row[4])
                winning_number_4 = parse_integer(row[5])
                winning_number_5 = parse_integer(row[6])
                winning_number_6 = parse_integer(row[7])
                winning_number_7 = parse_integer(row[8])
                bonus_number_1 = parse_integer(row[9])
                bonus_number_2 = parse_integer(row[10])
                
                # From Last (쉼표로 구분된 번호들)
                from_last = 'NULL'
                if len(row) > 11 and row[11] and row[11].strip():
                    from_last_str = row[11].replace('"', '').strip()
                    if from_last_str:
                        from_last = escape_sql_string(from_last_str)
                
                # 통계 정보
                low_count = parse_integer(row[12]) if len(row) > 12 else 'NULL'
                high_count = parse_integer(row[13]) if len(row) > 13 else 'NULL'
                odd_count = parse_integer(row[14]) if len(row) > 14 else 'NULL'
                even_count = parse_integer(row[15]) if len(row) > 15 else 'NULL'
                range_1_10 = parse_integer(row[16]) if len(row) > 16 else 'NULL'
                range_11_20 = parse_integer(row[17]) if len(row) > 17 else 'NULL'
                range_21_30 = parse_integer(row[18]) if len(row) > 18 else 'NULL'
                range_31_40 = parse_integer(row[19]) if len(row) > 19 else 'NULL'
                range_41_50 = parse_integer(row[20]) if len(row) > 20 else 'NULL'
                
                # INSERT 문 생성
                insert_sql = f"""INSERT INTO `lottery_result` (
    `draw`, `draw_date`,
    `winning_number_1`, `winning_number_2`, `winning_number_3`, `winning_number_4`,
    `winning_number_5`, `winning_number_6`, `winning_number_7`,
    `bonus_number_1`, `bonus_number_2`,
    `from_last`,
    `low_count`, `high_count`, `odd_count`, `even_count`,
    `range_1_10`, `range_11_20`, `range_21_30`, `range_31_40`, `range_41_50`
) VALUES (
    {draw}, {draw_date},
    {winning_number_1}, {winning_number_2}, {winning_number_3}, {winning_number_4},
    {winning_number_5}, {winning_number_6}, {winning_number_7},
    {bonus_number_1}, {bonus_number_2},
    {from_last},
    {low_count}, {high_count}, {odd_count}, {even_count},
    {range_1_10}, {range_11_20}, {range_21_30}, {range_31_40}, {range_41_50}
);"""
                
                sql_statements.append(insert_sql)
                count += 1
                
                if count % 100 == 0:
                    print(f"진행 중: {count}개 레코드 처리됨...")
                    
            except Exception as e:
                print(f"오류 발생 (행 {count + 2}): {e}")
                print(f"행 데이터: {row[:5]}...")
                continue
        
        sql_statements.append("")
        sql_statements.append(f"-- 총 {count}개 레코드 삽입 완료")
        
        # 파일에 저장
        with open(output_file_path, 'w', encoding='utf-8') as outfile:
            outfile.write('\n'.join(sql_statements))
        
        print(f"\n완료! 총 {count}개의 INSERT 문이 생성되었습니다.")
        print(f"출력 파일: {output_file_path}")

if __name__ == '__main__':
    csv_file = 'src/main/resources/db/migration/cool.csv'
    output_file = 'src/main/resources/db/migration/insert_lottery_data.sql'
    
    if not os.path.exists(csv_file):
        print(f"오류: CSV 파일을 찾을 수 없습니다: {csv_file}")
        exit(1)
    
    generate_insert_sql(csv_file, output_file)
