CREATE TABLE region
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(20) NOT NULL,
    name       VARCHAR(20) NOT NULL,
    sort_order INT         NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_region_code UNIQUE (code)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE event
(
    id              BIGINT         NOT NULL AUTO_INCREMENT,

    external_id     VARCHAR(64)    NOT NULL,
    source          VARCHAR(20)    NOT NULL,

    kind            VARCHAR(20)    NOT NULL,
    subject_type    VARCHAR(20)    NOT NULL,
    trust           VARCHAR(20)    NOT NULL,

    subject         VARCHAR(100)   NOT NULL,
    title           VARCHAR(200)   NULL,

    starts_on       DATE           NOT NULL,
    ends_on         DATE           NOT NULL,
    open_hours      VARCHAR(100)   NULL,
    starts_at       TIME           NULL,

    perks           VARCHAR(500)   NULL,
    conditions      VARCHAR(500)   NULL,

    source_url      VARCHAR(500)   NOT NULL,
    listing_url     VARCHAR(500)   NULL,
    reservation_url VARCHAR(500)   NULL,
    image_url       VARCHAR(500)   NULL,

    place_name      VARCHAR(100)   NOT NULL,
    place_address   VARCHAR(200)   NOT NULL,
    place_lat       DECIMAL(10, 7) NOT NULL,
    place_lng       DECIMAL(10, 7) NOT NULL,
    place_kind      VARCHAR(20)    NOT NULL,
    region_id       BIGINT         NOT NULL,

    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_event_external_id UNIQUE (external_id),
    CONSTRAINT fk_event_region FOREIGN KEY (region_id) REFERENCES region (id),
    CONSTRAINT ck_event_period CHECK (starts_on <= ends_on),
    CONSTRAINT ck_event_lat CHECK (place_lat BETWEEN -90 AND 90),
    CONSTRAINT ck_event_lng CHECK (place_lng BETWEEN -180 AND 180)
) DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
