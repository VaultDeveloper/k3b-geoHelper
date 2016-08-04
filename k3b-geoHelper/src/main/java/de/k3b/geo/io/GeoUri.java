/*
 * Copyright (c) 2015-2016 by k3b.
 *
 * This file is part of k3b-geoHelper library.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.k3b.geo.io;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.k3b.geo.api.GeoPointDto;
import de.k3b.geo.api.IGeoPointInfo;
import de.k3b.util.IsoDateTimeParser;

/**
 * Converts between a {@link IGeoPointInfo} and a uri {@link String}.
 *
 * Format:
 *
 * * geo:{lat}{,lon{,hight_ignore}}}{?q={lat}{,lon}{,hight_ignore}{(name)}}{&uri=uri}{&id=id}{&d=description}{&z=zmin{&z2=zmax}}{&t=timeOfMeasurement}
 *
 * Example (with {@link de.k3b.geo.io.GeoUri#OPT_FORMAT_REDUNDANT_LAT_LON} set):
 *
 * * geo:12.345,-56.7890123?q=12.345,-56.7890123(name)&z=5&z2=7&uri=uri&d=description&id=id&t=1991-03-03T04:05:06Z
 *
 * This should be compatible with standard http://tools.ietf.org/html/draft-mayrhofer-geo-uri-00
 * and with googlemap for android.
 *
 * This implementation has aditional non-standard parameters for LocationViewer clients.
 *
 * Created by k3b on 13.01.2015.
 */
public class GeoUri {
    /* constants that define behaviour of fromUri and toUri */

    /** Option for {@link GeoUri#GeoUri(int)}: */
    public static final int OPT_DEFAULT = 0;

    /** Option for {@link GeoUri#GeoUri(int)} to influence {@link #toUriString}: Add lat/long twice.
     *
     * Example with opton set (and understood by google):
     *
     * * geo:52.1,9.2?q=52.1,9.2
     *
     * Example with opton not set (and not understood by google):
     *
     * * geo:52.1,9.2
     *
     */
    public static final int OPT_FORMAT_REDUNDANT_LAT_LON = 1;

    /** Option for {@link GeoUri#GeoUri(int)} for {@link #fromUri(String)} :
     * If set try to get {@link IGeoPointInfo#getTimeOfMeasurement()},
     * {@link IGeoPointInfo#getLatitude()}, {@link IGeoPointInfo#getLongitude()},
     * {@link IGeoPointInfo#getName()} from other fields.
     *
     * Example:
     *
     * * "geo:?d=I was in (Hamburg) located at 53,10 on 1991-03-03T04:05:06Z"
     *
     * would set {@link IGeoPointInfo#getTimeOfMeasurement()},
     * {@link IGeoPointInfo#getLatitude()}, {@link IGeoPointInfo#getLongitude()},
     * {@link IGeoPointInfo#getName()} from {@link IGeoPointInfo#getDescription()} .
     */
    public static final int OPT_PARSE_INFER_MISSING = 0x100;

    /**
     * Default for url-encoding.
     */
    private static final String DEFAULT_ENCODING = "UTF-8";
    public static final String GEO_SCHEME = "geo:";
    public static final String AREA_SCHEME = "geoarea:";

    /* Regular expressions used by the parser.<br/>
       '(?:"+something+")"' is a non capturing group; "\s" white space */
    private final static String regexpName = "(?:\\s*\\(([^\\(\\)]+)\\))"; // i.e. " (hallo world)"
    private final static Pattern patternName = Pattern.compile(regexpName);
    private final static String regexpDouble = "([+\\-]?[0-9\\.]+)"; // i.e. "-123.456"
    private final static String regexpDoubleOptional = regexpDouble + "?";
    private final static String regexpCommaDouble = "(?:\\s*,\\s*" + regexpDouble + ")"; // i.e. " , +123.456"
    private final static String regexpCommaDoubleOptional = regexpCommaDouble + "?";
    private final static String regexpLatLonAlt = regexpDouble + regexpCommaDouble + regexpCommaDoubleOptional;
    private final static String regexpLatLonLatLon = regexpDouble + regexpCommaDouble + regexpCommaDouble + regexpCommaDouble;
    private final static Pattern patternLatLonAlt = Pattern.compile(regexpLatLonAlt);
    private final static Pattern patternLatLonLatLon = Pattern.compile(regexpLatLonLatLon);
    private final static Pattern patternTime = Pattern.compile("([12]\\d\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ)");

    private final static String regexpHref = "(?:\\s*href\\s?\\=\\s?['\"]([^'\"]*)['\"])"; // i.e. href='hallo'
    private final static Pattern patternHref = Pattern.compile(regexpHref);

    private final static String regexpSrc = "(?:\\s*src\\s?\\=\\s?['\"]([^'\"]*)['\"])"; // i.e. src='hallo'
    private final static Pattern patternSrc = Pattern.compile(regexpSrc);

    /* Current state */

    /** Formating/parsing options */
    private final int options;

    /** For uri-formatter: Next delimiter for a parameter. can be "?" or "&"  */
    private String delim;

    /** Create with options from OPT_xxx */
    public GeoUri(int options) {
        this.options = options;
    }

    /** Load {@link IGeoPointInfo} from uri-{@link String} */
    public IGeoPointInfo fromUri(String uri) {
        return fromUri(uri, new GeoPointDto());
    }

    /** Load {@link IGeoPointInfo} from uri-{@link String} into parseResult. */
    public <TGeo extends GeoPointDto>  TGeo fromUri(String uri, TGeo parseResult) {
        if (uri == null) return null;
        if (!uri.startsWith(GEO_SCHEME)) return null;

        int queryOffset = uri.indexOf("?");

        if (queryOffset >= 0) {
            String query = uri.substring(queryOffset+1);
            uri = uri.substring(0, queryOffset);
            HashMap<String, String> parmLookup = new HashMap<String, String>();
            String[] params = query.split("&");
            for (String param : params) {
                parseAddQueryParamToMap(parmLookup, param);
            }
            parseResult.setDescription(parmLookup.get(GeoUriDef.DESCRIPTION));
            parseResult.setLink(parmLookup.get(GeoUriDef.LINK));
            parseResult.setSymbol(parmLookup.get(GeoUriDef.SYMBOL));
            parseResult.setId(parmLookup.get(GeoUriDef.ID));
            parseResult.setZoomMin(GeoFormatter.parseZoom(parmLookup.get(GeoUriDef.ZOOM)));
            parseResult.setZoomMax(GeoFormatter.parseZoom(parmLookup.get(GeoUriDef.ZOOM_MAX)));
            // parameters from standard value and/or infered
            List<String> whereToSearch = new ArrayList<String>();
            whereToSearch.add(parmLookup.get(GeoUriDef.QUERY)); // lat lon from q have precedence over url-path
            whereToSearch.add(uri);
            whereToSearch.add(parmLookup.get(GeoUriDef.LAT_LON));

            final boolean inferMissing = isSet(GeoUri.OPT_PARSE_INFER_MISSING);
            if (inferMissing) {
                whereToSearch.add(parseResult.getDescription());
                whereToSearch.addAll(parmLookup.values());
            }

            parseResult.setName(parseFindFromPattern(patternName, parseResult.getName(), whereToSearch));
            parseResult.setTimeOfMeasurement(parseTimeFromPattern(parseResult.getTimeOfMeasurement(), parmLookup.get(GeoUriDef.TIME), whereToSearch));

            parseLatOrLon(parseResult, whereToSearch);

            if (parseResult.getName() == null) {
                parseResult.setName(parmLookup.get(GeoUriDef.NAME));
            }
            if (inferMissing) {
                parseResult.setLink(parseFindFromPattern(patternHref, parseResult.getLink(), whereToSearch));
                parseResult.setSymbol(parseFindFromPattern(patternSrc, parseResult.getSymbol(), whereToSearch));
            }
        } else {
            // no query parameter
            List<String> whereToSearch = new ArrayList<String>();
            whereToSearch.add(uri);
            parseLatOrLon(parseResult, whereToSearch);
        }
        return parseResult;
    }

    /** Load {@link GeoPointDto} from uri-{@link String} into parseResult. */
    public <TGeo extends GeoPointDto>  TGeo[] fromUri(String uri, TGeo[] parseResult) {
        if ((uri == null) || (parseResult == null) || (parseResult.length < 2)) return null;
        if (!uri.startsWith(AREA_SCHEME)) return null;

        Matcher m = parseFindWithPattern(patternLatLonLatLon, uri);

        if (m != null) {
            int nextCoord = 1;
            try {
                parseResult[0].setLatitude(GeoFormatter.parseLatOrLon(m.group(nextCoord++))).setLongitude(GeoFormatter.parseLatOrLon(m.group(nextCoord++)));
                parseResult[1].setLatitude(GeoFormatter.parseLatOrLon(m.group(nextCoord++))).setLongitude(GeoFormatter.parseLatOrLon(m.group(nextCoord++)));
                return parseResult;
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /** Infer name,time,link,symbol from textToBeAnalysed if the fields are not already filled. */
    public static GeoPointDto inferMissing(GeoPointDto parseResult, String textToBeAnalysed) {

        if (textToBeAnalysed != null) {
            List<String> whereToSearch = new ArrayList<String>();
            whereToSearch.add(textToBeAnalysed);

            parseResult.setName(parseFindFromPattern(patternName, parseResult.getName(), whereToSearch));
            parseResult.setTimeOfMeasurement(parseTimeFromPattern(parseResult.getTimeOfMeasurement(), null, whereToSearch));
            parseResult.setLink(parseFindFromPattern(patternHref, parseResult.getLink(), whereToSearch));
            parseResult.setSymbol(parseFindFromPattern(patternSrc, parseResult.getSymbol(), whereToSearch));
        }
        return parseResult;
    }

    /** Parsing helper: Convert array to list */
    private static List<String> toStringArray(String... whereToSearch) {
        return Arrays.asList(whereToSearch);
    }

    /** Parsing helper: Set first finding of lat and lon to parseResult */
    public static void parseLatOrLon(GeoPointDto parseResult, String... whereToSearch) {
        parseLatOrLon(parseResult, toStringArray(whereToSearch));
    }

    /** Parsing helper: Set first finding of lat and lon to parseResult */
    private static void parseLatOrLon(GeoPointDto parseResult, List<String> whereToSearch) {
        Matcher m = parseFindWithPattern(patternLatLonAlt, whereToSearch);

        if (m != null) {
            try {
                final String val = m.group(1);
                double lat = GeoFormatter.parseLatOrLon(val);
                double lon = GeoFormatter.parseLatOrLon(m.group(2));

                parseResult.setLatitude(lat).setLongitude(lon);
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }
    }

    /** Parsing helper: Get the first finding of pattern in whereToSearch if currentValue is not set yet.
     * Returns currentValue or content of first matching group of pattern. */
    private static String parseFindFromPattern(Pattern pattern, String currentValue, List<String> whereToSearch) {
        if ((currentValue == null) || (currentValue.length() == 0)) {
            Matcher m = parseFindWithPattern(pattern, whereToSearch);
            String found = (m != null) ? m.group(1) : null;
            if (found != null) {
                return found;
            }
        }
        return currentValue;
    }

    /** Parsing helper: Get the first datetime finding in whereToSearch if currentValue is not set yet.
     * Returns currentValue or finding as Date . */
    private static Date parseTimeFromPattern(Date currentValue, String stringValue, List<String> whereToSearch) {
        String match = parseFindFromPattern(IsoDateTimeParser.ISO8601_FRACTIONAL_PATTERN, stringValue, whereToSearch);

        if (match != null) {
            return IsoDateTimeParser.parse(match);
        }
        return currentValue;
    }

    /** Parsing helper: Returns the match of the first finding of pattern in whereToSearch. */
    private static Matcher parseFindWithPattern(Pattern pattern, List<String> whereToSearch) {
        if (whereToSearch != null) {
            for (String candidate : whereToSearch) {
                Matcher m = parseFindWithPattern(pattern, candidate);
                if (m != null) return m;
            }
        }
        return null;
    }

    private static Matcher parseFindWithPattern(Pattern pattern, String candidate) {
        if (candidate != null) {
            Matcher m = pattern.matcher(candidate);
            while (m.find() && (m.groupCount() > 0)) {
                return m;
            }
        }
        return null;
    }

    /** Parsing helper: Add a found query-parameter to a map for fast lookup */
    private void parseAddQueryParamToMap(HashMap<String, String> parmLookup, String param) {
        if (param != null) {
            String[] keyValue = param.split("=");
            if ((keyValue != null) && (keyValue.length == 2)) {
                try {
                    parmLookup.put(keyValue[0], URLDecoder.decode(keyValue[1], DEFAULT_ENCODING));
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    /**
     * Converts lat lon} into uri {@link String} representatino.
     *
     * For details see {@link #toUriString(IGeoPointInfo)}
     */
    public String toUriString(double latitude, double longitude, int zoomLevel) {
        return toUriString(new GeoPointDto(latitude, longitude, zoomLevel));
    }

    /**
     * Converts a {@link IGeoPointInfo} into uri {@link String} representatino.<br/>
     * <br/>
     * Format
     *
     * geo:{lat{,lon{,hight_ignore}}}{?q={lat}{,lon}{,hight_ignore}{(name)}}{&uri=uri}{&id=id}{&d=description}{&z=zmin{&z2=zmax}}{&t=timeOfMeasurement}
     */
    public String toUriString(IGeoPointInfo geoPoint) {
        StringBuffer result = new StringBuffer();
        result.append(GEO_SCHEME);
        formatLatLon(result, geoPoint);

        delim = "?";
        appendQueryParameter(result, GeoUriDef.QUERY, formatQuery(geoPoint), false);
        appendQueryParameter(result, GeoUriDef.ZOOM, geoPoint.getZoomMin());
        appendQueryParameter(result, GeoUriDef.ZOOM_MAX, geoPoint.getZoomMax());
        appendQueryParameter(result, GeoUriDef.LINK, geoPoint.getLink(), true);
        appendQueryParameter(result, GeoUriDef.SYMBOL, geoPoint.getSymbol(), true);
        appendQueryParameter(result, GeoUriDef.DESCRIPTION, geoPoint.getDescription(), true);
        appendQueryParameter(result, GeoUriDef.ID, geoPoint.getId(), true);
        if (geoPoint.getTimeOfMeasurement() != null) {
            appendQueryParameter(result, GeoUriDef.TIME, GeoFormatter.formatDate(geoPoint.getTimeOfMeasurement()), false);
        }

        return result.toString();
    }


    /** Creates area uri {@link String} from two bounding {@link IGeoPointInfo}-d */
    public String toUriString(IGeoPointInfo northEast, IGeoPointInfo southWest) {
        StringBuffer result = new StringBuffer();
        result.append(AREA_SCHEME);
        result.append(GeoFormatter.formatLatLon(northEast.getLatitude())).append(",");
        result.append(GeoFormatter.formatLatLon(northEast.getLongitude())).append(",");
        result.append(GeoFormatter.formatLatLon(southWest.getLatitude())).append(",");
        result.append(GeoFormatter.formatLatLon(southWest.getLongitude()));

        return result.toString();
    }

    /** Formatting helper: Adds name value to result. */
    private void appendQueryParameter(StringBuffer result, String paramName, int paramValue) {
        if (paramValue != IGeoPointInfo.NO_ZOOM) {
            appendQueryParameter(result, paramName, Integer.toString(paramValue), true);
        }
    }

    /** Formatting helper: Adds name value to result with optional encoding. */
    private void appendQueryParameter(StringBuffer result, String paramName, String paramValue, boolean urlEncode) {
        if (paramValue != null) {
            try {
                result.append(delim).append(paramName).append("=");
                if (urlEncode) {
                    paramValue = encode(paramValue);
                }
                result.append(paramValue);
                delim = "&";
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }

    /** Formatting helper: Adds lat/lon to result. */
    private void formatLatLon(StringBuffer result, IGeoPointInfo geoPoint) {
        if (geoPoint != null) {
            result.append(GeoFormatter.formatLatLon(geoPoint.getLatitude()));

            if (geoPoint.getLongitude() != IGeoPointInfo.NO_LAT_LON) {
                result
                        .append(",")
                        .append(GeoFormatter.formatLatLon(geoPoint.getLongitude()));
            }
        }
    }

    /** Formatting helper: Adds {@link IGeoPointInfo} fields to result. */
    private String formatQuery(IGeoPointInfo geoPoint) {
        // {lat{,lon{,hight_ignore}}}{(name)}{|uri{|id}|}{description}
        StringBuffer result = new StringBuffer();

        if (isSet(OPT_FORMAT_REDUNDANT_LAT_LON)) {
            formatLatLon(result, geoPoint);
        }

        if (geoPoint.getName() != null) {
            try {
                result.append("(").append(encode(geoPoint.getName())).append(")");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        if (result.length() == 0) return null;

        return result.toString();
    }

    /** Formatting helper: Executes url-encoding. */
    private String encode(String raw) throws UnsupportedEncodingException {
        return URLEncoder.encode(raw, DEFAULT_ENCODING);
    }

    /** Return true, if opt is set */
    private boolean isSet(int opt) {
        return ((options & opt) != 0);
    }
}
