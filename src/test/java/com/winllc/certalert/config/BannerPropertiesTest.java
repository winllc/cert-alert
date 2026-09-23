package com.winllc.certalert.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The banner is read straight off the bean by every template, so the bean has to be there
 * exactly once and under the name they ask for.
 */
@SpringBootTest(properties = {
    "cert-alert.banner.enabled=true",
    "cert-alert.banner.text=UNCLASSIFIED//FOUO",
    "cert-alert.banner.text-color=#000000",
    "cert-alert.banner.background=#c8e6c9",
    "cert-alert.banner.height=2rem"
})
@ActiveProfiles("test")
class BannerPropertiesTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private BannerProperties banner;

    /**
     * The colours and the height are written into a stylesheet rather than into escapable
     * text, so a value that is not one is replaced rather than passed through. A typo in a
     * properties file should cost the default, not the page.
     */
    @Test
    void aValueThatIsNotAColourOrALengthFallsBackToTheDefault() {
        BannerProperties properties = new BannerProperties();

        properties.setTextColor("#abc");
        assertThat(properties.getTextColor()).isEqualTo("#abc");
        properties.setTextColor("white");
        assertThat(properties.getTextColor()).isEqualTo("white");
        properties.setTextColor("red; } body { display: none } .x {");
        assertThat(properties.getTextColor()).isEqualTo(BannerProperties.DEFAULT_TEXT_COLOR);
        properties.setTextColor("#12345");
        assertThat(properties.getTextColor()).isEqualTo(BannerProperties.DEFAULT_TEXT_COLOR);

        properties.setBackground("url(http://elsewhere/x.png)");
        assertThat(properties.getBackground()).isEqualTo(BannerProperties.DEFAULT_BACKGROUND);

        properties.setHeight("2.5rem");
        assertThat(properties.getHeight()).isEqualTo("2.5rem");
        properties.setHeight("32px");
        assertThat(properties.getHeight()).isEqualTo("32px");
        properties.setHeight("100");
        assertThat(properties.getHeight()).isEqualTo(BannerProperties.DEFAULT_HEIGHT);
        properties.setHeight("10em; position: absolute");
        assertThat(properties.getHeight()).isEqualTo(BannerProperties.DEFAULT_HEIGHT);
    }

    /** A bar with nothing to say is a coloured stripe that shortens the page for no reason. */
    @Test
    void switchedOnWithNoTextIsNotShown() {
        BannerProperties properties = new BannerProperties();
        properties.setEnabled(true);
        assertThat(properties.isVisible()).isFalse();
        properties.setText("   ");
        assertThat(properties.isVisible()).isFalse();
        properties.setText("UNCLASSIFIED");
        assertThat(properties.isVisible()).isTrue();
        properties.setEnabled(false);
        assertThat(properties.isVisible()).isFalse();
    }

    @Test
    void thereIsOneBeanAndTheTemplatesCanNameIt() {
        assertThat(context.getBeanNamesForType(BannerProperties.class))
                .as("a second copy would bind one and leave the templates reading the other")
                .containsExactly("bannerProperties");
    }

    @Test
    void andItIsBoundFromTheProperties() {
        assertThat(banner.isVisible()).isTrue();
        assertThat(banner.getText()).isEqualTo("UNCLASSIFIED//FOUO");
        assertThat(banner.getTextColor()).isEqualTo("#000000");
        assertThat(banner.getBackground()).isEqualTo("#c8e6c9");
        assertThat(banner.getHeight()).isEqualTo("2rem");
    }
}
