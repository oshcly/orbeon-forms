<!--
  Copyright (C) 2017 Orbeon, Inc.

  This program is free software; you can redistribute it and/or modify it under the terms of the
  GNU Lesser General Public License as published by the Free Software Foundation; either version
  2.1 of the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU Lesser General Public License for more details.

  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  -->
<xsl:stylesheet version="2.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:fr="http://orbeon.org/oxf/xml/form-runner"
    xmlns:xh="http://www.w3.org/1999/xhtml"
    xmlns:xf="http://www.w3.org/2002/xforms"
    xmlns:xxf="http://orbeon.org/oxf/xml/xforms">

    <xsl:import href="oxf:/oxf/xslt/utils/copy-modes.xsl"/>

    <!-- ======== Migrate grid to 12-column format ======== -->

    <!-- No migration needed -->
    <!-- fr:grid → fr:grid/@edit-ref -->
    <xsl:template match="*:grid[exists(fr:c)]" mode="within-body">
        <xsl:copy>
            <xsl:attribute name="edit-ref"/>
            <xsl:apply-templates select="@* | node()" mode="#current"/>
        </xsl:copy>
    </xsl:template>

    <!-- Migration needed -->
    <xsl:template match="*:grid[empty(fr:c)]" mode="within-body">

        <xsl:variable
            xmlns:cell="java:org.orbeon.oxf.fb.Cell"
            name="cells-and-details"
            select="cell:findTdsWithPositionAndSize(.)"/>

        <xsl:copy>
            <xsl:attribute name="edit-ref"/>
            <xsl:apply-templates select="@*" mode="#current"/>

            <xsl:variable
                name="grid-width"
                select="$cells-and-details[1]"/>

            <xsl:variable
                name="grid-height"
                select="$cells-and-details[2]"/>

            <!--<xsl:attribute name="debug-grid-width"  select="$grid-width"/>-->
            <!--<xsl:attribute name="debug-grid-height" select="$grid-height"/>-->

            <xsl:choose>
                <xsl:when test="count($cells-and-details) > 2">
                    <xsl:for-each select="1 to ((count($cells-and-details) - 1) idiv 5)">
                        <xsl:variable name="group-index" select="."/>
                        <xsl:variable name="first-index" select="($group-index - 1) * 5 + 3"/>
                        <xsl:variable name="td"          select="$cells-and-details[$first-index]"/>

                        <xsl:element name="fr:c">

                            <xsl:apply-templates select="$td/(@* except (@colspan, @rowspan))" mode="#current"/>

                            <xsl:attribute name="y" select="$cells-and-details[$first-index + 1]"/>
                            <xsl:attribute name="x" select="$cells-and-details[$first-index + 2]"/>

                            <xsl:for-each select="$cells-and-details[$first-index + 3][. != 1]">
                                <xsl:attribute name="h" select="."/>
                            </xsl:for-each>

                            <xsl:for-each select="$cells-and-details[$first-index + 4][. != 1]">
                                <xsl:attribute name="w" select="."/>
                            </xsl:for-each>

                            <xsl:apply-templates select="$td/node()" mode="#current"/>

                        </xsl:element>
                    </xsl:for-each>
                </xsl:when>
                <xsl:otherwise>
                    <!-- Cannot convert and keep as is -->
                    <xsl:apply-templates select="@* | node()" mode="#current"/>
                </xsl:otherwise>
            </xsl:choose>

        </xsl:copy>

    </xsl:template>

</xsl:stylesheet>