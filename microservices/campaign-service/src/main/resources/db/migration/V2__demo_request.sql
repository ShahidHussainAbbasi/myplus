-- Book-a-Demo lead table (moved off the monolith's myplusdb).
CREATE TABLE demo_request (
  id             bigint NOT NULL AUTO_INCREMENT,
  full_name      varchar(255),
  work_email     varchar(255),
  company        varchar(255),
  country        varchar(255),
  phone          varchar(255),
  interest       varchar(255),
  company_size   varchar(255),
  message        varchar(2000),
  timezone       varchar(255),
  preferred_date varchar(255),
  locale         varchar(255),
  source         varchar(255),
  status         varchar(255),
  created_at     datetime(6),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
