package com.depromeet.piki.extractor.image.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundingBoxTest {

    @Test
    @DisplayName("정상 좌표면 BoundingBox 를 만든다")
    void createsBoxForValidCoordinates() {
        BoundingBox box = BoundingBox.ofNormalizedOrNull(100, 200, 800, 700);

        assertNotNull(box);
        assertEquals(100, box.yMin());
        assertEquals(200, box.xMin());
        assertEquals(800, box.yMax());
        assertEquals(700, box.xMax());
    }

    @Test
    @DisplayName("좌표가 하나라도 null 이면 null")
    void nullWhenAnyCoordinateNull() {
        assertNull(BoundingBox.ofNormalizedOrNull(null, 200, 800, 700));
        assertNull(BoundingBox.ofNormalizedOrNull(100, null, 800, 700));
        assertNull(BoundingBox.ofNormalizedOrNull(100, 200, null, 700));
        assertNull(BoundingBox.ofNormalizedOrNull(100, 200, 800, null));
    }

    @Test
    @DisplayName("0~1000 범위를 벗어나면 null")
    void nullWhenOutOfRange() {
        assertNull(BoundingBox.ofNormalizedOrNull(-1, 200, 800, 700));
        assertNull(BoundingBox.ofNormalizedOrNull(100, 200, 1001, 700));
    }

    @Test
    @DisplayName("min 이 max 이상이면(순서 역전·영폭) null")
    void nullWhenMinNotLessThanMax() {
        assertNull(BoundingBox.ofNormalizedOrNull(800, 200, 100, 700));
        assertNull(BoundingBox.ofNormalizedOrNull(100, 700, 800, 200));
        assertNull(BoundingBox.ofNormalizedOrNull(100, 200, 100, 700));
    }
}
